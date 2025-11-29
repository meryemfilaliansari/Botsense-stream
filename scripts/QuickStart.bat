@echo off
REM ==============================================================
REM BotSense-Stream - Script de Démarrage Rapide
REM ==============================================================

echo.
echo ===============================================
echo    BotSense-Stream Quick Start
echo    Detection Adaptative de Bots en Temps Reel
echo ===============================================
echo.

REM Vérifier Java
echo [1/8] Verification de Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo ERREUR: Java n'est pas installe ou pas dans le PATH
    echo Veuillez installer Java JDK 11 ou superieur
    pause
    exit /b 1
)
echo    OK - Java detecte

REM Vérifier Maven
echo [2/8] Verification de Maven...
mvn -version >nul 2>&1
if errorlevel 1 (
    echo ERREUR: Maven n'est pas installe ou pas dans le PATH
    echo Veuillez installer Apache Maven 3.6+
    pause
    exit /b 1
)
echo    OK - Maven detecte

REM Vérifier Docker
echo [3/8] Verification de Docker...
docker --version >nul 2>&1
if errorlevel 1 (
    echo AVERTISSEMENT: Docker n'est pas detecte
    echo Kafka et Zookeeper doivent etre demarres manuellement
    set SKIP_DOCKER=1
) else (
    echo    OK - Docker detecte
    set SKIP_DOCKER=0
)

REM Générer la structure si nécessaire
if not exist "botsense-stream" (
    echo [4/8] Generation de la structure du projet...
    
    if exist "ProjectStructureGenerator.java" (
        javac ProjectStructureGenerator.java
        java ProjectStructureGenerator
        echo    OK - Structure generee
    ) else (
        echo ERREUR: ProjectStructureGenerator.java non trouve
        pause
        exit /b 1
    )
) else (
    echo [4/8] Structure du projet existante detectee
)

cd botsense-stream

REM Compiler le projet
echo [5/8] Compilation du projet (peut prendre quelques minutes)...
call mvn clean install -DskipTests
if errorlevel 1 (
    echo ERREUR: Compilation echouee
    pause
    exit /b 1
)
echo    OK - Projet compile

REM Démarrer Kafka et Zookeeper
if "%SKIP_DOCKER%"=="0" (
    echo [6/8] Demarrage de Kafka et Zookeeper...
    docker-compose up -d
    if errorlevel 1 (
        echo ERREUR: Impossible de demarrer Kafka
        pause
        exit /b 1
    )
    
    echo    Attente du demarrage complet (30 secondes)...
    timeout /t 30 /nobreak >nul
    
    echo    Creation des topics Kafka...
    docker exec -it kafka kafka-topics.sh --create --if-not-exists --topic bot-traffic --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1 >nul 2>&1
    docker exec -it kafka kafka-topics.sh --create --if-not-exists --topic bot-detections --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1 >nul 2>&1
    echo    OK - Kafka demarre et topics crees
) else (
    echo [6/8] SKIP - Kafka doit etre demarre manuellement
    echo    Assurez-vous que Kafka est demarre sur localhost:9092
    pause
)

REM Vérifier les topics
if "%SKIP_DOCKER%"=="0" (
    echo [7/8] Verification des topics...
    docker exec -it kafka kafka-topics.sh --list --bootstrap-server localhost:9092
)

REM Lancer l'application
echo [8/8] Lancement de BotSense-Stream...
echo.
echo ===============================================
echo    SYSTEME EN COURS DE DEMARRAGE
echo ===============================================
echo.
echo Dashboard disponible sur: http://localhost:8080
echo.
echo Appuyez sur Ctrl+C pour arreter le systeme
echo.
echo ===============================================
echo.

java -jar target/botsense-stream-1.0.0.jar

REM Nettoyage en cas d'arrêt
echo.
echo Arret du systeme...
if "%SKIP_DOCKER%"=="0" (
    docker-compose down
)
echo Termine.
pause