package app.tvmouse.tv;

/** Costanti del protocollo telefono <-> TV (testo, una riga per comando, separatore '|'). */
final class Protocol {
    static final int TCP_PORT = 8765;
    static final int UDP_PORT = 8766;
    static final String DISCOVER = "TVMOUSE_DISCOVER";
    static final String HERE = "TVMOUSE_HERE";
    static final int MAX_LINE = 1024;

    private Protocol() {}
}
