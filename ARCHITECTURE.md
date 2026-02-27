# ARCHITECTURE — Starforge

## Назначение
`Starforge` — детерминированный генератор звездных систем с экспортом в MySQL. На входе каталог звезд (`stars.csv`), на выходе набор объектов системы (звезда/планеты/спутники) в таблице `StarSystems`.

## Технологический стек
- Java 21
- Maven
- JavaFX (desktop UI)
- Apache Commons CSV
- Jackson
- MySQL Connector/J

## Высокоуровневый поток
1. UI (`gui/App`) выбирает запись звезды и параметры генерации.
2. `core/io/StarCsvReader` читает исходные данные.
3. `core/gen/SystemGenerator` строит систему с детерминированным seed.
4. `core/physics/*` рассчитывает физику орбит, атмосфер, структуры гигантов и т.д.
5. `core/model/*` удерживает объектную модель результата.
6. `core/io/MySqlPerSystemTableExporter` записывает объекты в `StarSystems` (по `StarSystemID`).

## Слои
### 1) UI
- Пакет: `org.example.starforge.gui`
- Ключевые классы:
  - `App` — основной экран и orchestration;
  - `OrbitCanvas` — визуализация орбит;
  - `SystemText` — текстовое представление системы.

### 2) Generation core
- Пакет: `core/gen`
- Ключевые классы:
  - `SystemGenerator`, `EmbryoGenerator`, `MoonGenerator`, `DiskGenerator`, `OuterRingGenerator`.
- Роль: построение структуры системы и последовательность этапов генерации.

### 3) Physics
- Пакет: `core/physics`
- Ключевые классы:
  - `OrbitStabilityPhysics`, `AtmospherePhysics`, `AtmosphereEscapePhysics`, `GasAccretionPhysics`, `BiospherePhysics`.
- Роль: расчет производных физических параметров.

### 4) Domain model
- Пакет: `core/model`
- Ключевые сущности:
  - `StarModel`, `PlanetModel`, `MoonModel`, `SystemModel`, `Orbit`, перечисления типов.

### 5) IO and persistence
- Пакет: `core/io`
- Ключевые классы:
  - `StarCsvReader` — чтение входного каталога;
  - `MySqlPerSystemTableExporter` — экспорт в БД, в т.ч. correction flow.

### 6) Determinism
- Пакет: `core/random`
- Классы: `DeterministicRng`, `SeedUtil`
- Роль: воспроизводимость результата при одинаковом seed.

## Хранилище и контракты
- Основная таблица: `StarSystems`
- Типы объектов: `ObjectType` 1/2/3 (звезда/планета/спутник)
- Planet/moon payload: compact `PData` формат (см. `PData_Schema.md`)

## Интеграции
- Источник: `stars.csv`
- Приемник: MySQL `EXOLOG` (`StarSystems`)
- Downstream-потребители:
  - `ExodusServer` (runtime-данные систем)
  - `PlanetSurfaceGenerator` (входные астропараметры для генерации поверхности)

## Точки расширения
- Новые физические модели: `core/physics/*` + подключение в pipeline.
- Новые этапы генерации: `core/gen/*` с фиксированным порядком выполнения.
- Новые поля PData: синхронное изменение сериализации и схемы документации.

## Риски
- Рост связности между UI-логикой и orchestration генерации.
- Регрессы совместимости при изменении compact PData схемы.
- Потенциально длительные операции при крупных батчах/первом запуске.

## Быстрая навигация
- Entry point: `src/main/java/org/example/starforge/gui/App.java`
- Generation core: `src/main/java/org/example/starforge/core/gen/`
- Export: `src/main/java/org/example/starforge/core/io/MySqlPerSystemTableExporter.java`
