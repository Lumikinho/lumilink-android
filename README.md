# lumilink-android — celular (Kotlin, minSdk 26)

Lado Android do LumiLink: Foreground Service que conecta, pareia uma vez (QR)
e reconecta sozinho. App irmão: `lumilink-pc`.

## O que faz (só a base)

- `ConnectionService` (foreground, notificação fixa "Conectado a \<PC\>").
- Pareamento: lê o QR com Google Code Scanner, valida o certificado pelo
  `fingerprint` (pinning manual), envia `hello` com token + perfil.
- Reconexão sem QR: responde ao desafio com HMAC-SHA256; backoff 1s→30s.
- `ping/pong` 15s (3 faltas = caída); descoberta via `NsdManager`
  (`_lumilink._tcp`) com fallback para o último IP.
- Perfil com Photo Picker (sem permissões), avatar de iniciais, troca
  `profile`/`profile_photo*` por hash SHA-256.
- Toggle "Iniciar com o sistema" (BOOT_COMPLETED) + pedido de isenção de bateria.

## Como compilar

Pré-requisitos: Android Studio, SDK com `android-34`.

```
Abra android-app/ > Sync Gradle > Run 'app' (celular real, não emulador).
```

Ou via linha de comando (com `sdk.dir` em `local.properties`):

```
.\gradlew.bat assembleDebug   # APK em app/build/outputs/apk/debug/
```

## Estrutura

```
app/src/main/java/com/lumilink/app/
  transport/PhoneWebSocketClient.kt  (OkHttp + pinning manual)
  pairing/QrPairingParser.kt
  storage/PairStore.kt               (DataStore + EncryptedSharedPreferences)
  profile/ProfileStore.kt
  messaging/MessageRouter.kt         (roteador por type + exemplo echo)
  ConnectionService.kt, BootReceiver.kt
  ui/MainActivity.kt, ui/ScannerActivity.kt, ui/ProfileActivity.kt
```

## Registrar função nova (exemplo "echo")

```kotlin
router.register("echo_response") { _, msg ->
    log("[echo] eco: ${msg.payload.optString("text")}")
}
// enviar: client.send(Message.create("echo", JSONObject().put("text", "oi")))
```

## Como parear e testar

1. PC e celular no mesmo Wi-Fi. No PC: "Parear novo celular" (mostra o QR).
2. No celular: "Escanear QR" (Google Code Scanner — exige Play Services
   atualizado e internet na 1ª leitura).
3. Teste: desligue o Wi-Fi 10s (backoff e volta sozinha), troque nome/foto em
   "Meu perfil", use "Esquecer PC" (exige QR novo), reinicie o celular com o
   toggle ativo.

## Persistência

DataStore (`pc_device_id`, `pc_last_ip`, nomes, `start_on_boot`) +
`EncryptedSharedPreferences` (segredo) + `filesDir/profile.png` e `peer_<id>.png`.
`local.properties` (caminho do SDK) não é commitado.
