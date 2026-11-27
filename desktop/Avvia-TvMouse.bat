@echo off
rem Avvia TV Mouse su Windows (serve Java 11 o successivo: https://adoptium.net)
cd /d "%~dp0"
start "" javaw -jar TvMouse-PC.jar
