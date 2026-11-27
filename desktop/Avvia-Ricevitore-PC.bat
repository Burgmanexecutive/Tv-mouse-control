@echo off
rem Avvia il ricevitore: permette al telefono di controllare questo PC (serve Java 11 o successivo)
cd /d "%~dp0"
start "" javaw -jar TvMouse-Ricevitore-PC.jar
