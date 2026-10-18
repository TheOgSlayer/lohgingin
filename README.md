# JalebiLogin

Login/register screens for **JALEBI SMP** using Paper's native Dialog API.

**Requires Paper 1.21.6 or newer** (dialogs don't exist before that).

- Dialog titled JALEBI (gold-orange gradient) + SMP (white)
- First join: Register screen (password + confirm fields)
- Returning players: Login screen, 3 tries then kick
- Passwords stored hashed (PBKDF2) in `plugins/JalebiLogin/accounts.yml`

## Download
Go to the **Actions** tab, open the latest run, and download the `JalebiLogin` artifact (contains `JalebiLogin.jar`).
Put the jar in your server's `plugins/` folder.

## Build locally
`mvn package` (Java 21) -> `target/JalebiLogin.jar`
