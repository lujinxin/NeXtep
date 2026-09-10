# Building NeXtep

## Toolchain

- Android Studio with Android SDK 36.1 installed
- JDK 21 for Gradle and Android Gradle Plugin execution
- Gradle Wrapper 9.5.0, included in this repository
- Android Gradle Plugin 9.3.0

The application source is written in Kotlin. Java 17 in the Gradle configuration is the JVM source/bytecode compatibility level used by the Android toolchain; it does not mean that the application is implemented in Java. The project targets API 36, has a minimum API of 35, and resolves dependencies from Google Maven and Maven Central.

Create `local.properties` locally if Android Studio does not generate it:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

`local.properties` is machine-specific and must not be committed.

## Build commands

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

The debug APK is written below `app/build/outputs/apk/debug/`.

The release build currently has no repository-managed signing configuration. Configure signing outside version control before producing a distributable APK, and never commit keystores or signing passwords.

## Device validation

Because NeXtep hooks privileged system processes, validate changes on a recoverable test device and confirm that LSPosed can be disabled during recovery. Local device logs and diagnostic output should remain outside version control.
