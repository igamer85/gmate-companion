# gMate Companion

gMate Companion is a lightweight android app built in kotlin for the gMate project. the main idea behind making this was to let minecraft bedrock and mobile players use voice chat and basic session controls in the background without needing heavy third party apps open all the time.

> **Disclaimer:** **i am not a professional coder, and i have used a good amount of AI (Google Gemini) in my projects to help write and fix the code.** this is a personal hobby project shared under the MIT license "as is" with no guarantees, so **use it at your own risk.**
>
> unofficial community project. not approved by or associated with Mojang or Microsoft.

## what this app does

- **lightweight background companion:** runs quietly in the background while you play minecraft on phone.
- **voice chat and session controls:** lets you connect to your gMate voice/session room and control it easily, including via in-game commands.
- **encrypted local login storage:** saves your session token and username locally using android encrypted shared preferences (`SecureVault.kt`) instead of plain text files.

## how it works

when you open the app and sign in, `SecureVault` creates a master key through android keystore (AES256) and stores your session token safely on your device. after login, the app links your session with the gMate backend/bridge so when you join the server, your voice and session state stays synced in the background.

the signed release APKs are compiled automatically using github actions so nobody has to build it manually on pc unless they want to edit the code.

## how to install

1. go to the **Releases** page and download the latest signed `.apk` file.
2. tap the apk on your android phone (enable "install unknown apps" if android asks).
3. open the app, log in with your gMate account and allow microphone permission if you want to use voice.

check the **Wiki** tab if you want to know how the build workflow or vault works.

## license

released under the MIT License, check [LICENSE](LICENSE) file.
