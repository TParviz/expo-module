# Анализ проекта и описание ошибки

## Ошибка
```
Cannot find a nativeModule FirstModule
```

## Структура проекта

### ✅ Что настроено правильно:

1. **Модуль FirstModule создан корректно:**
   - Kotlin класс: `expo.modules.firstmodule.FirstModule.kt` ✅
   - TypeScript интерфейс: `FirstModule.ts` ✅
   - Web реализация: `FirstModule.web.ts` ✅
   - Конфигурация: `expo-module.config.json` ✅
   - Android build.gradle настроен ✅

2. **Экспорт модуля:**
   - `modules/first-module/index.ts` экспортирует модуль ✅
   - `modules/first-module/src/FirstModule.ts` использует `requireNativeModule('FirstModule')` ✅

3. **Использование в приложении:**
   - `app/index.tsx` импортирует `showButton` из модуля ✅

### ❌ Проблемы, вызывающие ошибку:

#### 1. **Модуль НЕ добавлен в app.json plugins**
   - **Текущее состояние:** Модуль удален из массива `plugins` в `app.json`
   - **Проблема:** Expo autolinking не может найти и зарегистрировать локальный модуль
   - **Решение:** Добавить `"./modules/first-module"` в массив `plugins` в `app.json`

#### 2. **Отсутствует package.json в модуле**
   - **Текущее состояние:** В директории `modules/first-module/` нет `package.json`
   - **Проблема:** Expo autolinking требует `package.json` для обнаружения модулей
   - **Решение:** Создать `package.json` в `modules/first-module/`

#### 3. **Модуль не включен в Android build**
   - **Текущее состояние:** В `android/settings.gradle` нет явного включения модуля
   - **Проблема:** Expo autolinking должен автоматически включать модули, но без правильной конфигурации это не работает
   - **Решение:** После добавления в `app.json` и создания `package.json`, выполнить `npx expo prebuild --clean`

## Детальный анализ файлов

### app.json
```json
"plugins": [
  "expo-router",
  ["expo-splash-screen", {...}]
  // ❌ ОТСУТСТВУЕТ: "./modules/first-module"
]
```

### modules/first-module/index.ts
```typescript
export { default } from './src/FirstModule';  // ✅ Правильно
export function showButton(text: string): string {
    return FirstModule.showButton(text);  // ✅ Исправлено
}
```

### modules/first-module/src/FirstModule.ts
```typescript
export default requireNativeModule<FirstModule>('FirstModule');
// ✅ Правильно - ищет модуль с именем "FirstModule"
```

### modules/first-module/expo-module.config.json
```json
{
  "android": {
    "modules": ["expo.modules.firstmodule.FirstModule"]  // ✅ Правильно
  }
}
```

### modules/first-module/android/src/main/java/expo/modules/firstmodule/FirstModule.kt
```kotlin
Name("FirstModule")  // ✅ Имя модуля совпадает с requireNativeModule('FirstModule')
```

## Цепочка регистрации модуля

1. **app.json plugins** → Указывает Expo, где искать модуль
2. **package.json в модуле** → Позволяет autolinking обнаружить модуль
3. **expo-module.config.json** → Указывает классы модулей для каждой платформы
4. **Android build.gradle** → Автоматически включается через autolinking
5. **MainApplication.kt** → Автоматически регистрирует модули через ExpoModulesPackage

## Решение

### Шаг 1: Создать package.json для модуля
Создать файл `modules/first-module/package.json`:
```json
{
  "name": "first-module",
  "version": "0.7.6",
  "main": "index.ts"
}
```

### Шаг 2: Добавить модуль в app.json
Добавить в массив `plugins`:
```json
"plugins": [
  "expo-router",
  ["expo-splash-screen", {...}],
  "./modules/first-module"
]
```

### Шаг 3: Пересобрать проект
```bash
npx expo prebuild --clean
npm run android
```

## Дополнительные проверки

### Проверка регистрации модуля:
После пересборки проверить в `android/build/generated/autolinking/autolinking.json` наличие записи о `first-module`.

### Проверка в runtime:
Модуль должен быть доступен через `requireNativeModule('FirstModule')` без ошибок.

## Вывод

**Основная причина ошибки:** Модуль не зарегистрирован в системе autolinking Expo из-за отсутствия записи в `app.json` и отсутствия `package.json` в модуле.

**Критичность:** Высокая - без этих изменений модуль никогда не будет найден.

**Время на исправление:** ~5 минут

