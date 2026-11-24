package app.tvmouse.common;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Canale cifrato tra telefono e TV, senza dipendenze Android (condiviso dai due moduli).
 *
 * Handshake: ogni parte genera una chiave EC P-256 effimera e invia la parte pubblica; dal segreto
 * ECDH e dal PIN (stringa vuota se la TV non lo richiede) si ricavano due chiavi AES-256, una per
 * direzione. Poi ogni messaggio e' un frame AES-GCM con nonce a contatore implicito.
 *
 * Con PIN sbagliato le chiavi non coincidono: il primo messaggio non si decifra e la TV risponde
 * con un frame di rifiuto in chiaro. Il PIN non viaggia mai in rete.
 *
 * Frame: [lunghezza: 2 byte][tipo: 1 byte][dati]. La lunghezza comprende il byte del tipo.
 */
public final class SecureChannel {

    /** Il messaggio ricevuto non si decifra: chiavi diverse (PIN sbagliato) o dati alterati. */
    public static final class AuthFailedException extends IOException {
        public AuthFailedException() {
            super("autenticazione fallita");
        }
    }

    /** L'altro lato ha rifiutato la connessione (frame di errore in chiaro). */
    public static final class RejectedException extends IOException {
        public final String code;

        public RejectedException(String code) {
            super("rifiutato: " + code);
            this.code = code;
        }
    }

    private static final int T_KEY = 1;
    private static final int T_DATA = 2;
    private static final int T_ERR = 3;
    private static final int MAX_FRAME = 4096;
    private static final int MAX_LINE = 1500;

    private final DataInputStream in;
    private final OutputStream out;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec recvKey;
    private long sendCounter;
    private long recvCounter;

    private SecureChannel(DataInputStream in, OutputStream out, SecretKeySpec sendKey, SecretKeySpec recvKey) {
        this.in = in;
        this.out = out;
        this.sendKey = sendKey;
        this.recvKey = recvKey;
    }

    public static SecureChannel connectAsClient(InputStream in, OutputStream out, String pin) throws IOException {
        return handshake(in, out, pin == null ? "" : pin, true);
    }

    public static SecureChannel acceptAsServer(InputStream in, OutputStream out, String pin) throws IOException {
        return handshake(in, out, pin == null ? "" : pin, false);
    }

    /** Invia una riga cifrata. */
    public synchronized void send(String line) throws IOException {
        byte[] plain = line.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_LINE) throw new IOException("messaggio troppo lungo");
        byte[] cipher;
        try {
            cipher = crypt(Cipher.ENCRYPT_MODE, sendKey, sendCounter, plain);
        } catch (GeneralSecurityException e) {
            throw new IOException("cifratura: " + e.getMessage(), e);
        }
        sendCounter++;
        writeFrame(out, T_DATA, cipher);
    }

    /** Invia un rifiuto in chiaro (es. "PIN"). Non lancia eccezioni. */
    public synchronized void sendReject(String code) {
        try {
            writeFrame(out, T_ERR, code.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // la connessione e' gia' chiusa
        }
    }

    /**
     * Legge e decifra una riga. Ritorna null a fine flusso.
     *
     * @throws RejectedException se l'altro lato ha inviato un rifiuto
     * @throws AuthFailedException se il messaggio non si decifra
     */
    public String receive() throws IOException {
        Frame f = readFrame(in);
        if (f == null) return null;
        if (f.type == T_ERR) throw new RejectedException(new String(f.payload, StandardCharsets.UTF_8));
        if (f.type != T_DATA) throw new IOException("frame inatteso");
        byte[] plain;
        try {
            plain = crypt(Cipher.DECRYPT_MODE, recvKey, recvCounter, f.payload);
        } catch (GeneralSecurityException e) {
            throw new AuthFailedException();
        }
        recvCounter++;
        if (plain.length > MAX_LINE) throw new IOException("messaggio troppo lungo");
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ interni

    private static SecureChannel handshake(InputStream rawIn, OutputStream out, String pin, boolean client)
            throws IOException {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();
            byte[] mine = kp.getPublic().getEncoded();

            DataInputStream in = new DataInputStream(new BufferedInputStream(rawIn));
            byte[] theirs;
            if (client) {
                writeFrame(out, T_KEY, mine);
                theirs = readKeyFrame(in);
            } else {
                theirs = readKeyFrame(in);
                writeFrame(out, T_KEY, mine);
            }

            PublicKey peer = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(theirs));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(kp.getPrivate());
            ka.doPhase(peer, true);
            byte[] secret = ka.generateSecret();

            byte[] clientPub = client ? mine : theirs;
            byte[] serverPub = client ? theirs : mine;

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] salt = sha.digest(("tvmouse-v1|" + pin).getBytes(StandardCharsets.UTF_8));
            byte[] prk = hmac(salt, secret);
            sha.reset();
            sha.update(clientPub);
            sha.update(serverPub);
            byte[] transcript = sha.digest();

            byte[] c2s = hmac(prk, concat("c2s".getBytes(StandardCharsets.US_ASCII), transcript, new byte[]{1}));
            byte[] s2c = hmac(prk, concat("s2c".getBytes(StandardCharsets.US_ASCII), transcript, new byte[]{1}));

            return new SecureChannel(in, out,
                    new SecretKeySpec(client ? c2s : s2c, "AES"),
                    new SecretKeySpec(client ? s2c : c2s, "AES"));
        } catch (GeneralSecurityException e) {
            throw new IOException("handshake: " + e.getMessage(), e);
        }
    }

    private static byte[] readKeyFrame(DataInputStream in) throws IOException {
        Frame f = readFrame(in);
        if (f == null) throw new EOFException("connessione chiusa");
        if (f.type != T_KEY || f.payload.length < 40 || f.payload.length > 200) {
            throw new IOException("handshake non valido");
        }
        return f.payload;
    }

    private static byte[] crypt(int mode, SecretKeySpec key, long counter, byte[] data)
            throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, nonce));
        return c.doFinal(data);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    private static final class Frame {
        int type;
        byte[] payload;
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readUnsignedShort();
        } catch (EOFException e) {
            return null;
        }
        if (len < 1 || len > MAX_FRAME) throw new IOException("dimensione frame non valida");
        byte[] body = new byte[len];
        in.readFully(body);
        Frame f = new Frame();
        f.type = body[0] & 0xFF;
        f.payload = Arrays.copyOfRange(body, 1, len);
        return f;
    }

    private static void writeFrame(OutputStream out, int type, byte[] payload) throws IOException {
        int len = payload.length + 1;
        if (len > MAX_FRAME) throw new IOException("frame troppo grande");
        byte[] frame = new byte[2 + len];
        frame[0] = (byte) (len >> 8);
        frame[1] = (byte) len;
        frame[2] = (byte) type;
        System.arraycopy(payload, 0, frame, 3, payload.length);
        out.write(frame);
        out.flush();
    }
}
