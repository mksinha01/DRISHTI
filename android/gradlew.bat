@echo off
setlocal
if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

set "GRADLE_EXE=C:\Users\mksin\.gradle\wrapper\dists\gradle-9.1.0-all\7wzd0jkjit61aq2p43wpjgij9\gradle-9.1.0\bin\gradle.bat"
if exist "%GRADLE_EXE%" (
    call "%GRADLE_EXE%" %*
) else (
    echo Gradle executable not found at %GRADLE_EXE%
    exit /b 1
)
