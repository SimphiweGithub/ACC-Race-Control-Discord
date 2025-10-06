@echo off
cd /d "%~dp0"

REM Set Discord bot settings here (or via environment)
set JAVA_OPTS=%JAVA_OPTS% "-Ddiscord.bot.token=MTQxOTYyMTc2OTcwNjM0NDQ3OA.GEhIIt.ohMgXUYnD2RZFz1MQMhIC3XsFKgCvx5NDUf99E" "-Ddiscord.channel.id=1418228156187414600"
start /b .\jre\bin\javaw.exe %JAVA_OPTS% -splash:data/loading.png -cp .\lib\* racecontrol.Main
