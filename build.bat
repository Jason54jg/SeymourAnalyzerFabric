@echo off
cd /d "%~dp0"
echo Building Seymour Analyzer for all Minecraft versions...
call gradlew.bat chiseledBuild --console=plain
echo.
echo Build complete! Check build\libs\<version>\ for the JAR files.
pause

