# lumilink-android — nó P2P (Kotlin, minSdk 26)

Nó simétrico do LumiLink: **servidor (TLS próprio + mDNS) e cliente** (OkHttp) ao
mesmo tempo. Conversa com PC, Android ou ambos. App irmão: `lumilink-pc`.
Protocolo: `PROTOCOL.md` (idêntico nas 3 combinações).

## O que faz (só a base)

- `ConnectionService` (foreground) com `PeerManager` multi-par.
- Servidor `wss` próprio (porta aleatória persistida) + cliente OkHttp com pinning.
- Anuncia e descobre `_lumilink._tcp` (`NsdManager`); só o menor `deviceId` disca.
- Pareamento: mostra QR próprio (com código de 6 dígitos) **ou** escaneia/digita
  (manual: IP + porta + código); auth mútua HMAC; backoff independente por par.
- Principal: lista de pareados (foto, nome, estado) + Parear / Manual / Escanear /
  Atualizar; detalhe do par (último IP, Reconectar agora, Esquecer).
- Perfil com Photo Picker; `profile_update` enviado ao salvar.

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

## HyperIsland (HyperOS)

As notificações (status do service, ping, clipboard) levam extras
`miui.focus.param` + `miui.focus.pics` — em HyperOS com permissão de foco,
aparecem na ilha; sem suporte caem para notificação normal sozinhas.

## Mídia na ilha

Com **Acesso a notificações** liberado (botão na tela principal), o app lê a
sessão de mídia ativa (música, vídeo) e mostra `▶ título — artista` na ilha e
na tela principal. Sem mídia, a notificação sai sozinha.

## Controle remoto do PC (MediaSession)

O PC espelha o que toca lá (`media_pc`: título, artista, capa 300px, posição);
o celular mostra como **player de mídia do sistema** (notificação MediaStyle,
lockscreen, painel rápido, e ilha por cima) com Anterior/Tocar/Pausar/Próxima
e barra de seek — os comandos voltam como `media_cmd`. Vale em qualquer
fabricante; requer `androidx.media:media`.
