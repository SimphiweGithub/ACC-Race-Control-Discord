@echo off
cd /d "%~dp0"

REM Set Discord bot settings here (or via environment)
set JAVA_OPTS=%JAVA_OPTS% 
start /b .\jre\bin\javaw.exe %JAVA_OPTS% -splash:data/loading.png -cp .\lib\* racecontrol.Main
