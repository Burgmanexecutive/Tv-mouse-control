# TV Mouse

Usa il telefono come telecomando, mouse e tastiera per una TV Android. Funziona anche con l'hotspot del telefono acceso e la TV collegata a quell'hotspot: nessun router e nessun internet necessari, basta che i due dispositivi siano nella stessa rete.

Il progetto contiene **due app**, perche' una sola non basta:

| App | Dove si installa | Cosa fa |
|---|---|---|
| `phone` (TV Mouse) | telefono | trackpad, tasti, tastiera, elenco app, scelta della TV |
| `tv` (TV Mouse Ricevitore) | TV Android | disegna il puntatore, esegue clic, scrive il testo, riceve i comandi |

Un'app sul telefono da sola non puo' disegnare un puntatore sulla TV: per questo serve il ricevitore.

> **Stato del codice:** scritto con cura ma **non ancora compilato ne' provato su dispositivi reali**. Ho provato in locale solo il canale cifrato (PIN giusto, sbagliato, assente). Se la compilazione segnala un errore, mandamelo e lo correggo.

## 1. Ottenere gli APK

### Opzione A: GitHub (nessun programma da installare)
1. Crea un repository su GitHub e carica tutto il contenuto di questa cartella (comprese le cartelle `.github` e `shared`).
2. Vai nella scheda **Actions**: parte da solo il workflow "Build APK".
3. Quando e' verde, apri l'esecuzione e scarica l'artefatto `tvmouse-apk`: contiene `phone-debug.apk` e `tv-debug.apk`.

### Opzione B: Android Studio
1. Apri questa cartella con Android Studio (versione recente, JDK 17).
2. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)** per ciascun modulo (`phone`, `tv`).
3. Oppure da terminale: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).

Gli APK finiscono in `phone/build/outputs/apk/debug/` e `tv/build/outputs/apk/debug/`.

## 2. Installazione

- **Telefono:** copia `phone-debug.apk`, aprilo e consenti l'installazione da origini sconosciute.
- **TV:** installa `tv-debug.apk` con uno di questi metodi: chiavetta USB con un file manager, app "Send Files to TV", app "Downloader", oppure `adb install tv-debug.apk` da PC.

## 3. Configurazione della TV (una sola volta)

1. Apri **TV Mouse Ricevitore** sulla TV: vedi **indirizzo IP** e **PIN** a 6 cifre.
2. Premi **"Apri impostazioni di accessibilita'"**, scegli **TV Mouse Ricevitore** e attivalo. Serve per mostrare il puntatore, simulare i clic, scrivere il testo e leggere l'elenco delle app.
   - Se l'interruttore e' grigio (Android 13 o piu' recente, app non installata dallo store): da PC esegui `adb shell appops set app.tvmouse.tv ACCESS_RESTRICTED_SETTINGS allow` e riprova.
3. **Protezione:** sotto il PIN c'e' l'interruttore **"PIN richiesto"**. Con PIN attivo (consigliato) il telefono deve conoscerlo; spento, qualunque telefono nella rete puo' collegarsi (vedi sezione Sicurezza).
4. Per **canali, spegnimento, frecce, tasti numerici, riproduzione e Wake-on-LAN** (opzionale, vedi tabella): attiva il debug ADB di rete.
   - Impostazioni > Preferenze dispositivo > Informazioni > premi 7 volte su "Build" per sbloccare le Opzioni sviluppatore.
   - Nelle Opzioni sviluppatore attiva **Debug USB** e **Debug di rete** (su alcune TV si chiama "Debug wireless"/"ADB via rete").
   - Nell'app TV scrivi la porta (di solito 5555) e premi **"Salva e prova ADB"**. Quando compare la richiesta sullo schermo scegli **"Consenti sempre"**.

Dopo l'attivazione dell'accessibilita' il ricevitore parte da solo anche dopo un riavvio della TV: non serve riaprire l'app.

## 4. Uso con il telefono

Alla prima apertura compare il dialogo di collegamento: premi **"Cerca TV in rete"** (oppure scrivi l'IP mostrato sulla TV), inserisci il PIN (vuoto se la TV non lo richiede) e premi **Collega**. La TV viene salvata con il suo nome e al prossimo avvio ci si ricollega da soli.

**In alto:** stato della connessione e tre pulsanti.
- **TV:** elenco delle TV salvate. Tocca per collegarti; **tieni premuto** su una TV per "Accendi con Wake-on-LAN" o "Elimina". "Aggiungi o cerca una TV" apre il dialogo di collegamento.
- **Opzioni:** velocita' del puntatore, vibrazione sui tasti, tasti volume del telefono.
- **Scrivi:** apre la casella di testo (vedi sotto).

**Sempre visibili:** CH -/+, VOL -/+ (tenendo premuto si ripete), MUTE, Indietro, Home e **Spegni** (tieni premuto, per evitare spegnimenti accidentali).

**Schede:**
- **Mouse:** trackpad. Un dito muove il puntatore, tocco rapido = clic, tocco lungo = clic lungo, due dita su/giu' = scorri.
- **Frecce:** frecce, **OK**, **Menu**, **Ingressi** (cambio HDMI) e, sotto, un'area di navigazione: scorri il dito per premere le frecce, tocca per OK. Le frecce si ripetono tenendole premute.
- **Numeri:** tasti 0-9 (per digitare il numero di canale) e comandi di riproduzione (precedente, indietro, play/pausa, avanti, successivo).
- **App:** elenco delle app installate sulla TV; tocca per avviarne una. "Aggiorna" ricarica l'elenco.

**Scrivi (tastiera del telefono):** sulla TV seleziona un campo di testo col puntatore, poi premi **Scrivi**: compare una casella con la tastiera del telefono. Quello che digiti, cancelli o incolli compare subito nel campo della TV, nel punto del cursore. **Incolla** inserisce il contenuto degli appunti, **Invio** (o il tasto invio della tastiera) conferma, ad esempio per avviare una ricerca, e svuota la casella. **Azzera** svuota solo la casella sul telefono: usalo se hai modificato il testo direttamente sulla TV. Il cursore della casella resta sempre in fondo, quindi scrivi di seguito.

**Tasti volume del telefono:** con l'app aperta e la TV collegata comandano il volume della TV (si disattiva dalle Opzioni).

### Con l'hotspot del telefono
Attiva l'hotspot, collega la TV a quella rete e apri le due app: la ricerca prova il broadcast su ogni interfaccia (compresa quella dell'hotspot) e, se non risponde nessuno, scansiona la sottorete. Se non trova nulla, inserisci l'IP che vedi nell'app TV.

### Piu' telefoni
La TV accetta un solo telefono alla volta. Se un secondo telefono si collega, il primo riceve l'avviso e non si riconnette da solo (cosi' non si "rubano" la TV a vicenda).

## 5. Cosa funziona senza ADB e cosa no

| Funzione | Senza ADB | Con ADB attivo sulla TV |
|---|---|---|
| Puntatore, clic, scorrimento, Indietro, Home | si | si |
| Volume e mute | si (volume di sistema della TV) | si (come il telecomando fisico, anche con HDMI-CEC) |
| Frecce e OK | in parte: sposta il focus di sistema, **non funziona in tutte le app** | si, come il telecomando fisico |
| Menu, Ingressi, tasti 0-9, riproduzione | **no** | si |
| Canale su/giu' | **no** | si, se e' in primo piano un'app TV in diretta che li supporta |
| Spegnimento (standby) | **no** | si |
| Scrivere testo dal telefono | si, nei campi di testo standard (accenti ed emoji compresi) | si, anche nei campi che non si lasciano modificare e nelle password, ma solo con caratteri ASCII base |
| Elenco e avvio delle app | si; su alcune TV con Android 10 o piu' l'avvio da servizio in secondo piano puo' essere bloccato | si, piu' affidabile |
| Wake-on-LAN | **no**: il MAC della TV non e' leggibile da un'app normale | si: il MAC viene letto via ADB al primo collegamento |

Android non permette a un'app normale di simulare i tasti di un telecomando: per questo si usa l'ADB locale. Limiti noti, che non ho potuto verificare senza una TV:
- **Latenza:** ogni tasto via ADB avvia un comando sulla TV, quindi ha qualche decimo di secondo di ritardo. Se tieni premuta una freccia, i tasti in eccesso in coda vengono scartati per non accumulare ritardo.
- **ADB su Android 11 o piu':** alcune TV usano il "Debug wireless" con porta casuale e abbinamento: in quel caso il ponte ADB potrebbe non collegarsi e restano solo le funzioni della colonna "Senza ADB".
- **Spegnimento:** invia `KEYCODE_SLEEP`. Se sulla tua TV non fa nulla, in `RemoteService.java` sostituisci `KeyEvent.KEYCODE_SLEEP` con `KeyEvent.KEYCODE_POWER`.
- **Wake-on-LAN:** la TV in standby deve restare in ascolto sulla rete e l'opzione (a volte chiamata "Accendi con Wi-Fi/LAN") deve essere abilitata. Molte TV Android non la supportano via Wi-Fi: in quel caso serve il telecomando fisico. Con l'hotspot acceso funziona solo se la TV resta collegata all'hotspot da spenta.

## 6. Sicurezza

- **Cifratura:** ogni connessione usa uno scambio di chiavi (ECDH P-256) e cifra tutti i messaggi con AES-256-GCM. Il **PIN non viaggia mai in rete**: entra nel calcolo delle chiavi, quindi con PIN sbagliato la TV non riesce nemmeno a decifrare il primo messaggio e rifiuta il collegamento. Dopo un PIN errato la TV rallenta i tentativi successivi (1 s, 2 s... fino a 10 s).
- **Limite onesto:** chi *ascolta soltanto* la rete non puo' leggere i comandi ne' il testo che scrivi. Un attaccante *attivo*, che si inserisce nella rete proprio mentre ti colleghi, potrebbe invece provare a indovinare offline un PIN di 6 cifre. Per l'uso domestico e con l'hotspot personale e' un rischio molto basso; evita reti pubbliche.
- **PIN richiesto = NO:** la connessione resta cifrata contro chi ascolta, ma **chiunque nella rete puo' collegarsi alla TV**, muovere il puntatore e scrivere nei campi (password comprese). Usalo solo su reti di cui ti fidi, per esempio il tuo hotspot.
- Il servizio di accessibilita' puo' simulare tocchi e leggere il campo di testo selezionato (per scriverci nel punto giusto; nei campi password non legge nulla). Il codice non memorizza ne' invia altrove quel testo. Attivalo solo per questa app e disattivalo da Impostazioni quando non serve.
- Non e' implementata la "conferma sulla TV" al primo collegamento: richiederebbe una finestra che prenda il comando del telecomando sopra qualunque app, e non ho potuto verificarla senza una TV.

## 7. Regolazioni rapide

- Velocita' del puntatore: dalle **Opzioni** del telefono (o la costante `SENSITIVITY` in `phone/.../TrackpadView.java`).
- Porta di rete usata: `Protocol.TCP_PORT` / `UDP_PORT` (uguali nei due moduli).
- Se su un Android recentissimo la ricerca non trova nulla, controlla nelle info dell'app se e' richiesto un permesso "Rete locale" o "Dispositivi nelle vicinanze" e concedilo.

## Struttura

```
shared/ canale cifrato (SecureChannel), usato da entrambe le app
phone/  app telefono: MainActivity, TrackpadView, RemoteClient, Discovery, Prefs, WakeOnLan
tv/     app TV: RemoteService (accessibilita', cursore, testo, tasti, app), ConnectionServer, AdbBridge, MainActivity
```

Protocollo: messaggi di testo, uno per riga, dentro frame cifrati. Telefono -> TV: `AUTH|nome`, `MOVE|dx|dy`, `CLICK`, `LONG`, `SCROLL|1|-1`, `BACK`, `HOME`, `VOLUP`, `VOLDOWN`, `MUTE`, `CHUP`, `CHDOWN`, `POWER`, `KEY|codice`, `TEXT|base64`, `DEL|n`, `ENTER`, `APPS`, `LAUNCH|pacchetto`, `PING`. TV -> telefono: `OK|nome`, `PONG`, `APP|pacchetto|nome(base64)`, `APPSEND`, `MAC|mac1,mac2`, `BYE|REPLACED`, `ERR|NOFIELD`, `ERR|CHARS`, `ERR|ADB_OFF`, `ERR|ADB_FAIL`. Un rifiuto del PIN arriva come frame in chiaro `PIN`.
