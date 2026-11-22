# Teyvat Radar

Mapa flutuante e transparente que fica **por cima do Genshin Impact** no Android. Assim dá para ver onde estão os inimigos sem trocar de app (no celular, trocar de app costuma fazer o jogo reiniciar).

## Download

- **Última versão:** [TeyvatRadar.apk](https://github.com/aniwah2/teyvat-radar/releases/latest/download/TeyvatRadar.apk)
- **Todas as versões:** [Releases](https://github.com/aniwah2/teyvat-radar/releases)

## Recursos (v0.1.0)

- Bolinha flutuante ⚔ que abre e fecha o mapa
- Mapa interativo oficial do HoYoLAB, com filtros de inimigos que ficam salvos
- Janela que dá para mover e redimensionar (◢), com transparência ajustável (− / +)
- 👻 Modo fantasma: o mapa continua visível e os toques vão para o jogo (opacidade máxima de 80%, por regra do Android)
- ⌨ Teclado sob demanda, para buscar um inimigo pelo nome
- Posição, tamanho e transparência ficam lembrados entre os usos

## Instalação

1. Baixe o APK e permita "instalar apps desconhecidos".
2. Abra o app e libere **Exibir sobre outros apps** e **Notificações**.
3. No HyperOS (Xiaomi/Poco), vá em Configurações → Apps → Teyvat Radar:
   - em **Economia de bateria**, escolha **Sem restrições**;
   - em **Outras permissões**, ligue **Exibir janelas pop-up em segundo plano**.
4. Toque em **Iniciar radar e abrir o Genshin**.

## Compilar

O app é escrito em Java puro, sem Gradle nem Android Studio, e compila no Ubuntu:

```bash
sudo apt install openjdk-21-jdk-headless aapt apksigner zipalign dalvik-exchange
# android.jar (API 34 para os recursos e 35 para compilar) em ../sdk/
./build.sh
```

A chave de assinatura (`radar.keystore`) **não fica no repositório**. Mantenha uma cópia sua: sem ela, as próximas versões não instalam por cima da anterior.

## Versionamento

Segue [SemVer](https://semver.org/lang/pt-BR/). Cada versão tem uma tag `vX.Y.Z` e uma Release com o APK anexado. O `versionName`/`versionCode` do `AndroidManifest.xml` acompanha a tag.

| Versão | Novidades |
| ------ | --------- |
| 0.1.0 | Primeira versão: mapa flutuante, transparência, modo fantasma |
