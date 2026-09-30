# MogRitual Resource Pack Template

Этот шаблон уже настроен на sound event:

`mogritual:ya_tebya_mognu`

Для работы звука положи разрешённый к использованию OGG-файл по пути:

`assets/mogritual/sounds/ya_tebya_mognu.ogg`

После этого:

1. Упакуй **содержимое** папки resource-pack-template в ZIP так, чтобы `pack.mcmeta` лежал в корне ZIP.
2. Размести ZIP по прямой HTTPS-ссылке.
3. Посчитай SHA-1 ZIP-файла.
4. В `plugins/MogRitual/config.yml` укажи:
   - `resource-pack.enabled: true`
   - `resource-pack.url`
   - `resource-pack.sha1`
   - `resource-pack.require-for-ritual: true`

Плагин отслеживает статус загрузки pack и может не запускать ritual до `SUCCESSFULLY_LOADED`.


## Аудиофрагмент для текущей сборки

Текущая длительность ритуала — 5 секунд (100 ticks).

Используемый участок исходной записи: **0:05 → 0:10**.

Финальный файл ресурспака должен называться:

`assets/mogritual/sounds/ya_tebya_mognu.ogg`
