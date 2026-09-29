# ServerGuardian

Главный performance/diagnostics core для Leaf/Paper 1.21.11.

## Что он делает

- мониторит TPS, MSPT, память, entities, chunks и количество pending Bukkit tasks;
- использует состояния NORMAL / PRESSURE / EMERGENCY с hysteresis;
- публикует ServerHealthService для других плагинов;
- в EMERGENCY может безопасно удалять только старые обычные dropped items;
- показывает pending tasks по plugin owner;
- сохраняет snapshot при переходах между состояниями;
- делает read-only JAR scan с SHA-256 и сигнальными признаками.

## Чего он НЕ делает

- не удаляет plugin JAR;
- не утверждает, что plugin вредоносный по одному совпадению;
- не вызывает System.gc();
- не выгружает чанки насильно;
- не удаляет mobs/pets/named entities;
- не может "исправить" CPU loop внутри стороннего плагина — такой источник нужно найти и убрать.

## Команды

- /guardian status
- /guardian report
- /guardian plugins
- /guardian cleanup
- /guardian scan
- /guardian reload
- /guardian mode auto|normal|pressure|emergency

## Интеграция

Другие наши плагины смогут получить ServerHealthService через Bukkit ServicesManager и уменьшить cosmetic load:

- NORMAL: multiplier 1.0
- PRESSURE: multiplier 0.55
- EMERGENCY: multiplier 0.25

Значения настраиваются в config.yml.
