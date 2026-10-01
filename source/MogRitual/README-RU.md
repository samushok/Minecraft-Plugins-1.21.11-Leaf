# MogRitual 1.6 — «Я тебя могну»

Cinematic ritual plugin для Leaf / Paper 1.21.11, Java 21.

## Основной flow

1. Два разных игрока рядом пишут `я тебя могну`.
2. После первого игрока второй должен присоединиться за 12 секунд.
3. После второго идёт 2-секундный gradient countdown: `2 → 1 → НАЧАЛИ`.
4. Затем запускается 5-секундный cinematic dance:
   - независимая third-person camera для каждого участника;
   - настоящие модели игроков вращаются/танцуют;
   - по 3 BlockDisplay вокруг каждого игрока;
   - particles + custom sound `mogritual:ya_tebya_mognu`.
5. После танца сервер выбирает ровно одного победителя через `ThreadLocalRandom.nextBoolean()`: 50/50.
6. Проигравший сразу видит `ТЫ ПРОИГРАЛ`.
7. Победителю запускается 3-секундный MOG CASE:
   - ItemDisplay с player-head/chest texture;
   - TextDisplay над кейсом;
   - быстрый preview наград с плавным замедлением;
   - финальный заранее выбранный reward.
8. Выполняется только команда финальной награды победителя.
9. После успешной выдачи: gradient title `ТЫ ВЫИГРАЛ` + reward, particle burst и cosmetic fireworks.
10. При `cooldown.start: SUCCESS` cooldown ставится обоим участникам только после успешной финальной выдачи. Если победитель уже выбран и намеренно прерывает финальную case-stage выходом/смертью/сменой мира, попытка засчитывается, чтобы нельзя было reroll-ить 50/50.

## Производительность и cleanup

Плагин не использует ArmorStand или NPC.

Во время dance:
- максимум 6 временных BlockDisplay (3 на игрока);
- до 2 camera BlockDisplay;
- один repeating Bukkit task.

Во время 3-секундного case:
- 1 ItemDisplay;
- 1 TextDisplay;
- один короткий repeating task.

Cleanup выполняется при нормальном завершении, abort, logout-path и onDisable. Camera всегда возвращается на игрока, а временные entities удаляются.

Если установлен `ServerGuardian`, cosmetic particle counts учитывают его `cosmeticMultiplier()`.

## Config

Основные секции:
- `general` — включение trigger;
- `resource-pack` — managed resource pack URL/SHA-1/status;
- `trigger` — фраза, timeout, радиусы, chat-controller compatibility;
- `cooldown` — PLAYER/GLOBAL, SUCCESS/START, persistence, bypass;
- `pre-ritual-countdown` — 2→1→start titles/colors/sounds;
- `ritual` — 5-second dance, camera, blocks, particles, sound;
- `winner-result` — win/lose titles и цвета;
- `winner-case` — 3-second case animation, texture, sounds, fireworks, reward execution;
- `chance-command` — формат `/mogchance`;
- `messages` — сообщения;
- `rewards` — enabled/display-name/weight/chance/commands.

`trigger.required-players` в 1.5.1 всегда должен быть `2`. Migration v9 автоматически исправляет старые значения.

## Cooldown

По умолчанию:
- `scope: PLAYER`
- `start: SUCCESS`
- `seconds: 21600`

При успешной выдаче награды cooldown получают оба участника.

Permission `mogritual.cooldown.bypass` предназначен для админского тестирования. В GLOBAL mode полностью bypass-тест не ставит global cooldown всему серверу.

`cooldown.properties` сохраняется через временный файл + atomic move, а повреждённый файл при загрузке не валит plugin startup.

## Resource pack / музыка

Sound event:

`mogritual:ya_tebya_mognu`

Текущий clip: **0:05 → 0:10**, ровно 5 секунд.

Managed pack:
- `resource-pack.enabled: true`
- `resource-pack.url: "<direct HTTPS ZIP>"`
- `resource-pack.sha1: "<40-char SHA-1>"`
- `resource-pack.required: true`
- `resource-pack.require-for-ritual: true`
- `resource-pack.send-on-join: true`

Плагин использует фиксированный UUID resource pack и отслеживает `PlayerResourcePackStatusEvent` именно для него.

После `/mogritual reload` старые pack-status очищаются и текущий pack повторно отправляется онлайн-игрокам, если manager включён.

Если managed pack включён, custom sound играется только игроку со статусом `SUCCESSFULLY_LOADED`; игрок без него получает vanilla fallback. Если `require-for-ritual: true`, оба участника повторно проверяются перед самым стартом.

Шаблон pack лежит в `source/MogRitual/resource-pack-template/`.

## 50/50 и MOG CASE

Победитель выбирается независимо от reward:

`ThreadLocalRandom.current().nextBoolean()`

Это означает ровно 50/50 между двумя участниками на каждой попытке.

После выбора победителя выбирается reward по `weight`.

Case-stage длится по умолчанию 60 ticks = 3 секунды. Preview reward names обновляются быстро в начале и замедляются к финалу.

Если winner case display неожиданно становится invalid, награда не выдаётся и SUCCESS cooldown не ставится.

Если победитель уже выбран и выходит/умирает/меняет мир во время case-stage, попытка засчитывается cooldown для защиты от reroll exploit.

## Награды

Каждая награда имеет:
- `enabled`
- `display-name`
- `weight` — реальный вес выбора;
- `chance` — display-value для `/mogchance`;
- `commands`.

Executors:
- `CONSOLE:<command>`
- `PLAYER:<command>`

Placeholders:
- `%player%`
- `%uuid%`
- `%reward%`

Command dispatch проверяется по boolean result. Если финальная reward-команда возвращает false или бросает RuntimeException, игрок не получает ложное success-сообщение и SUCCESS cooldown не применяется.

Важно: PLAYER-команды зависят от прав конкретного победителя. Их нужно один раз подтвердить на production server с установленными kit/cases/relic plugins.

Для безопасного теста:

`winner-case.execute-reward-commands: false`

Тогда визуальная сцена проходит, но reward и cooldown не выдаются.

## Команды

- `/mogritual status`
- `/mogritual pack`
- `/mogritual reload`
- `/mogritual validate`
- `/mogritual resetcooldown <player|all>`
- `/mogritual clearpending`
- `/mogchance`

## Chat-controller

`trigger.accept-cancelled-chat: true` позволяет видеть trigger на серверах, где отдельный chat-controller отменяет стандартный Paper chat event и отображает сообщение самостоятельно.

Это поведение обязательно нужно проверить вместе с реальным mute/chat plugin production-сборки: некоторые mute plugins тоже используют cancelled chat event.

## Migration

Текущий `config-version: 12`.

Migration поддерживает старые конфиги и поэтапно добавляет:
- v3 — chat-controller compatibility;
- v4 — dance visuals;
- v5 — cinematic camera + custom sound;
- v6 — managed resource pack;
- v7 — 5-second timing;
- v8 — countdown + 50/50 winner case;
- v9 — строго 2-player flow, перенос execute flag в `winner-case`, удаление legacy roulette settings;
- v10 — явная миграция новых audio-fallback и release-hardening настроек для серверов, уже сохранивших ранний v9 config;
- v11 — приглашение ближайшего игрока на MOG-ритуал с title, chat-инструкцией и звуком.

## CI / release checks

GitHub Actions:
- Java 21;
- Leaf 1.21.11 build 179 bootstrap;
- compile;
- plugin/config/static regression assertions;
- v2→v9 migration;
- intentionally malformed `cooldown.properties`;
- real Leaf smoke boot;
- artifact upload.

Перед production всё равно нужны два environment-specific теста:
1. визуально проверить camera/case framing двумя настоящими Minecraft-клиентами;
2. подтвердить реальные PLAYER reward commands на production plugin stack.


## Invitation 1.5.2

Когда первый игрок пишет trigger-фразу, плагин ищет ближайшего подходящего игрока в `trigger.gather-radius`.

Приглашённый получает:
- gradient title `✦ ВАС ПРИГЛАСИЛИ ✦`;
- subtitle с ником пригласившего, trigger-фразой и оставшимся временем;
- понятную chat-инструкцию, что нужно написать `я тебя могну`;
- короткий notification sound.

Первый игрок получает подтверждение, кому отправлено приглашение. Если рядом нет подходящего игрока, он получает отдельное сообщение.

Приглашение не меняет основную механику: ритуал всё равно запускается только после того, как второй допустимый игрок сам пишет trigger-фразу.


## Concurrent rituals 1.6

MogRitual больше не использует один глобальный `ritualActive`.

Каждая пара получает независимую `RitualSession`:
- собственные участники;
- собственную cinematic camera/dance state;
- собственный MOG CASE;
- независимый winner/cleanup.

Настройка:

```yaml
concurrency:
  # 3 = одновременно до 3 пар / 6 игроков.
  # 0 = без лимита.
  max-active-rituals: 3
```

Один игрок не может одновременно находиться в двух активных ритуалах.
Приглашения привязаны к конкретному инициатору, поэтому параллельные пары не смешивают участников.
