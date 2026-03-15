@echo off
echo Building MCCTimer Agent...
call gradlew.bat build
if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED!
    pause
    exit /b 1
)
echo BUILD SUCCESSFUL!
echo Output JAR: build\libs\mcctimer-agent-1.0-SNAPSHOT.jar
pause
