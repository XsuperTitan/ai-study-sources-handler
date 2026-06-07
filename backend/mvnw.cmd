@ECHO OFF
SETLOCAL
SET "BASE_DIR=%~dp0"
SET "MAVEN_VERSION=3.9.11"
SET "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"

IF NOT EXIST "%MAVEN_CMD%" (
  ECHO Downloading Apache Maven %MAVEN_VERSION%...
  IF NOT EXIST "%MAVEN_HOME%" MKDIR "%MAVEN_HOME%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$archive=Join-Path $env:TEMP 'apache-maven-%MAVEN_VERSION%-bin.zip'; $extract=Join-Path $env:TEMP 'maven-wrapper'; Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile $archive; if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }; Expand-Archive -Force $archive $extract; Copy-Item -Recurse -Force (Join-Path $extract 'apache-maven-%MAVEN_VERSION%\*') '%MAVEN_HOME%'; Remove-Item -Recurse -Force $extract; Remove-Item -Force $archive"
  IF ERRORLEVEL 1 EXIT /B 1
)

CALL "%MAVEN_CMD%" %*
SET "MVNW_EXIT_CODE=%ERRORLEVEL%"
ENDLOCAL & EXIT /B %MVNW_EXIT_CODE%
