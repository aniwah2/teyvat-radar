# Teyvat Radar

Mapa flutuante e transparente que fica **por cima do Genshin Impact** no Android. Assim dá para ver onde estão os inimigos sem trocar de app (no celular, trocar de app costuma fazer o jogo reiniciar).

## Download

- **Última versão:** [TeyvatRadar.apk](https://github.com/aniwah2/teyvat-radar/releases/latest/download/TeyvatRadar.apk)
- **Windows (mais recente):** [TeyvatRadar-Windows.exe](https://github.com/aniwah2/teyvat-radar/releases/download/desktop-latest/TeyvatRadar-Windows.exe)
- **Todas as versões:** [Releases](https://github.com/aniwah2/teyvat-radar/releases)

## Como funciona (v0.2.0)

1. Pelo serviço de Acessibilidade, o app olha a tela cerca de 3 vezes por segundo, só enquanto o Genshin está aberto, para reconhecer o mapa. Nada é gravado nem enviado.
2. Quando o mapa do jogo abre, o botão ⚔ aparece no canto esquerdo.
3. No botão você escolhe o que quer ver (inimigos, animais, plantas…) e toca em **Aplicar**.
4. O app reconhece qual pedaço de Teyvat está na tela, lendo a barra de zoom e comparando com o mapa oficial, e desenha os ícones no lugar certo.

Os dados (pontos, ícones e a "impressão digital" do mapa) são gerados pelo workflow **Dados do mapa** a partir do mapa interativo oficial da HoYoLAB e publicados no branch `data`.

## Recursos da v0.1.0 (janela flutuante, substituída na v0.2.0)

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

Projeto Gradle (Java + OpenCV 4.10). O workflow **Compilar app** gera o APK sem assinatura no branch `build`; ele é assinado com a chave do projeto e colocado em `dist/`, o que dispara a Release.

```bash
gradle :app:assembleRelease
```

A chave de assinatura (`radar.keystore`) **não fica no repositório**. Mantenha uma cópia sua: sem ela, as próximas versões não instalam por cima da anterior.

## Versionamento

Segue [SemVer](https://semver.org/lang/pt-BR/). Cada versão tem uma tag `vX.Y.Z` e uma Release com o APK anexado. O `versionName`/`versionCode` do `AndroidManifest.xml` acompanha a tag.

| Versão | Novidades |
| ------ | --------- |
| 0.5.1 | Remove a ocultação automática; mantém minimapa discreto e correção fora do jogo |
| 0.5.0 | Oculta o que já foi feito até renascer (removido na 0.5.1); minimapa discreto; ícones não ficam fora do jogo |
| 0.4.0 | Marcadores no minimapa, com rastreamento da posição; dados atualizados sozinhos |
| 0.3.1 | Radar não desliga com o gravador/Game Turbo; aviso de ativo; diagnóstico |
| 0.3.0 | Radar via Acessibilidade (funciona junto com a gravação de tela), liga sozinho com o jogo, escala mais precisa |
| 0.2.0 | Radar sobre o mapa do jogo: botão só com o mapa aberto, filtros com ícones, marcadores alinhados |
| 0.1.0 | Primeira versão: mapa flutuante, transparência, modo fantasma |

## Windows

Programa para PC em `desktop/` (Python + OpenCV + PySide6), com a mesma técnica do app Android. Ele fica na bandeja do Windows e liga sozinho com o Genshin. O botão aparece com o mapa aberto (M), e o minimapa se calibra sozinho na primeira vez que você abre e fecha o mapa. O workflow **Desktop (Windows)** gera o `.exe`, roda um autoteste no Windows e publica a release `desktop-vX.Y.Z`.

| Versão Windows | Novidades |
| -------------- | --------- |
| 0.1.0 | Primeira versão: mapa com filtros e ícones; minimapa com calibração automática |
