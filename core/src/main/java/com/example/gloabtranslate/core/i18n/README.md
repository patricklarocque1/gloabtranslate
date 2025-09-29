# Multi-Language Support (i18n) Implementation

This directory contains the complete internationalization (i18n) implementation for the Global Translate app, providing comprehensive multi-language support with RTL (Right-to-Left) language handling.

## Components Overview

### 1. StringResources.kt
Centralized string resource management for multi-language support.

**Features:**
- Dynamic string loading with locale management
- Support for 12 languages: English, Spanish, French, German, Italian, Portuguese, Russian, Chinese, Japanese, Korean, Arabic, Hindi
- Fallback string handling when localized versions are unavailable
- Language name mapping and display name generation
- RTL language detection

**Usage:**
```kotlin
val stringResources = StringResources.getInstance(context)
val localizedString = stringResources.getString(R.string.app_name)
val localizedStringForLocale = stringResources.getStringForLocale(Locale("es"), R.string.app_name)
```

### 2. LocaleFormatter.kt
Locale-specific formatting utilities for dates, times, numbers, and currencies.

**Features:**
- Date and time formatting with locale-specific patterns
- Number formatting with locale-specific separators
- Currency formatting with proper currency symbols
- Percentage formatting
- File size formatting
- Relative time formatting (e.g., "2 hours ago")
- Confidence percentage formatting

**Usage:**
```kotlin
val formatter = LocaleFormatter.getInstance(context)
val formattedDate = formatter.formatDate(Date(), Locale("es"))
val formattedCurrency = formatter.formatCurrency(99.99, "USD", Locale("es"))
```

### 3. RTLSupport.kt
Right-to-Left language support utilities for Arabic, Hebrew, and other RTL languages.

**Features:**
- RTL language detection
- Text direction management
- View layout direction handling
- RTL-aware margin and padding calculations
- Text mirroring for RTL display
- RTL character detection in text content
- Gravity and alignment adjustments

**Usage:**
```kotlin
val rtlSupport = RTLSupport.getInstance(context)
val isRTL = rtlSupport.isRTLLanguage("ar")
rtlSupport.applyRTLDirection(view, "ar")
```

### 4. LanguageLayoutManager.kt (in app/ui/i18n/)
Manages language-specific UI layouts and RTL support integration.

**Features:**
- Dynamic layout switching based on language selection
- RTL-specific layout resource management
- Language-specific drawable resource handling
- RTL-aware gravity and margin calculations
- Text formatting for language display
- Supported language information management

**Usage:**
```kotlin
val layoutManager = LanguageLayoutManager.getInstance(context)
layoutManager.applyLanguageLayout(view, "ar")
val rtlLayout = layoutManager.getLanguageLayout(R.layout.fragment_translation_result, "ar")
```

## Language Support

### Supported Languages
- **English (en)** - Default language
- **Spanish (es)** - Complete translation
- **French (fr)** - Complete translation
- **German (de)** - Complete translation
- **Italian (it)** - Complete translation
- **Portuguese (pt)** - Complete translation
- **Russian (ru)** - Complete translation
- **Chinese (zh)** - Complete translation
- **Japanese (ja)** - Complete translation
- **Korean (ko)** - Complete translation
- **Arabic (ar)** - Complete translation with RTL support
- **Hindi (hi)** - Complete translation

### RTL Languages
- Arabic (ar)
- Hebrew (he) - Supported in RTLSupport
- Persian (fa) - Supported in RTLSupport
- Urdu (ur) - Supported in RTLSupport

## Resource Structure

### String Resources
- `values/strings.xml` - Default English strings
- `values-es/strings.xml` - Spanish translations
- `values-ar/strings.xml` - Arabic translations (RTL)
- Additional language directories can be added as needed

### Layout Resources
- `layout/` - Default LTR layouts
- `layout-rtl/` - RTL-specific layouts
- RTL layouts include proper text alignment, gravity, and direction settings

## Integration Examples

### Basic Usage
```kotlin
// Get localized string
val appName = stringResources.getString(R.string.app_name)

// Format date for current locale
val formattedDate = localeFormatter.formatDate(Date())

// Check if current language is RTL
val isRTL = rtlSupport.isRTLLocale()

// Apply language-specific layout
languageLayoutManager.applyLanguageLayout(view, "ar")
```

### Advanced Usage
```kotlin
// Format text for RTL display
val rtlText = rtlSupport.formatTextForRTL("مرحبا", "ar")

// Get RTL-aware margins
val (leftMargin, rightMargin) = languageLayoutManager.getLanguageMargin(16, 16, "ar")

// Format currency with locale-specific formatting
val currency = localeFormatter.formatCurrency(99.99, "USD", Locale("es"))
```

## Best Practices

### 1. String Resources
- Always use `StringResources.getInstance(context)` to get localized strings
- Provide fallback strings for missing translations
- Use parameterized strings for dynamic content

### 2. RTL Support
- Always check `isRTLLanguage()` before applying RTL-specific logic
- Use `applyRTLDirection()` for view hierarchy
- Apply RTL text direction to TextViews with `applyRTLTextDirection()`

### 3. Layout Management
- Use `LanguageLayoutManager` for dynamic layout switching
- Create RTL-specific layouts in `layout-rtl/` directory
- Test with both LTR and RTL languages

### 4. Formatting
- Use `LocaleFormatter` for all date, time, number, and currency formatting
- Always specify locale for consistent formatting
- Handle edge cases for different locale patterns

## Testing

### Unit Tests
- Test string resource loading for all supported languages
- Test RTL language detection and text direction
- Test locale-specific formatting patterns
- Test fallback behavior for missing translations

### UI Tests
- Test layout switching between LTR and RTL languages
- Test text alignment and gravity in RTL layouts
- Test margin and padding calculations
- Test drawable resource switching

## Future Enhancements

### Planned Features
- Additional language support (Thai, Vietnamese, Dutch, etc.)
- Dynamic language switching without app restart
- Voice-over support for accessibility
- Bi-directional text handling
- Language-specific font support
- Cultural date/time formatting

### Performance Optimizations
- String resource caching
- Layout pre-loading for common languages
- Memory optimization for large string sets
- Lazy loading of language-specific resources

## Dependencies

### Core Dependencies
- Android Context for resource access
- Java Locale for language/country codes
- Android View system for UI components

### Optional Dependencies
- AndroidX ViewCompat for layout direction support
- Custom drawable resources for RTL-specific icons

## Troubleshooting

### Common Issues
1. **Missing translations**: Check if string resource exists in target language directory
2. **RTL layout issues**: Verify RTL-specific layout files are in `layout-rtl/` directory
3. **Text direction problems**: Ensure `applyRTLTextDirection()` is called on TextViews
4. **Formatting inconsistencies**: Check if correct locale is passed to formatter methods

### Debug Tips
- Use `isRTLLanguage()` to verify RTL detection
- Check `getCurrentLocale()` for current language setting
- Verify string resource loading with `getStringForLocale()`
- Test with different device language settings
