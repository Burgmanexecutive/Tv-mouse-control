#!/bin/sh
# Avvia il ricevitore: permette al telefono di controllare questo computer (serve Java 11 o successivo)
cd "$(dirname "$0")" && exec java -jar TvMouse-Ricevitore-PC.jar
