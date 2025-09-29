# Permission Management System

A comprehensive permission management system for the GloabTranslate Android application that provides rationale explanations, state persistence, recovery mechanisms, and privacy policy integration.

## Components

### 1. PermissionManager
**Location**: `:core/permissions/PermissionManager.kt`

Handles permission requests with detailed rationale explanations, state tracking, and user-friendly permission management.

**Key Features**:
- Detailed rationale explanations for each permission
- Permission categorization (AUDIO, NETWORK, NOTIFICATIONS, ESSENTIAL)
- State tracking (GRANTED, DENIED, DENIED_PERMANENTLY, NOT_REQUESTED, REVOKED)
- Reactive state updates with Flow
- Permission request analytics

**Usage**:
```kotlin
val permissionManager = PermissionManager.getInstance(context)

// Get rationale for a permission
val rationale = permissionManager.getPermissionRationale(Manifest.permission.RECORD_AUDIO)

// Request a single permission
permissionManager.requestPermission(activity, Manifest.permission.RECORD_AUDIO) { result ->
    when (result) {
        PermissionManager.PermissionResult.GRANTED -> // Handle granted
        PermissionManager.PermissionResult.DENIED -> // Handle denied
        // ...
    }
}

// Get essential permissions
val essentialPermissions = permissionManager.getEssentialPermissions()
```

### 2. PermissionPreferences
**Location**: `:core/data/preferences/PermissionPreferences.kt`

Persists permission states, request history, user choices, and analytics data for comprehensive tracking and recovery.

**Key Features**:
- Permission state persistence
- Request history tracking (up to 100 entries)
- User choice recording
- Analytics data collection
- Rationale shown tracking
- Settings opened tracking

**Usage**:
```kotlin
val permissionPreferences = PermissionPreferences.getInstance(context)

// Save permission state
permissionPreferences.savePermissionState(
    permission = Manifest.permission.RECORD_AUDIO,
    state = "GRANTED",
    isEssential = true,
    category = "AUDIO"
)

// Record permission request
permissionPreferences.recordPermissionRequest(
    permission = Manifest.permission.RECORD_AUDIO,
    result = "GRANTED",
    wasRationaleShown = true,
    userAction = "user_granted"
)

// Get analytics
val analytics = permissionPreferences.getPermissionAnalytics()
```

### 3. PermissionRecovery
**Location**: `:core/permissions/PermissionRecovery.kt`

Provides intelligent recovery mechanisms for revoked or denied permissions with multiple strategies.

**Key Features**:
- Multiple recovery strategies (IMMEDIATE, DELAYED, USER_INITIATED, GRACEFUL_DEGRADATION, DISABLE_FEATURE)
- Automatic recovery triggers
- Recovery attempt limiting and cooldowns
- Recovery state monitoring
- Integration with permission preferences

**Usage**:
```kotlin
val permissionRecovery = PermissionRecovery.getInstance(context, permissionManager, permissionPreferences)

// Trigger recovery
permissionRecovery.triggerRecovery(
    permission = Manifest.permission.RECORD_AUDIO,
    trigger = PermissionRecovery.RecoveryTrigger.APP_STARTUP,
    activity = activity,
    userContext = "app_startup"
)

// Add recovery listener
permissionRecovery.addRecoveryListener(object : PermissionRecovery.RecoveryListener {
    override fun onRecoveryCompleted(permission: String, success: Boolean, userAction: String?) {
        // Handle recovery result
    }
    // ... other methods
})
```

### 4. PrivacyManager
**Location**: `:core/privacy/PrivacyManager.kt`

Manages privacy policy compliance, user consent, and data collection transparency.

**Key Features**:
- Privacy policy version management
- User consent tracking
- Data collection settings
- Privacy event logging
- Compliance reporting
- Consent withdrawal support

**Usage**:
```kotlin
val privacyManager = PrivacyManager.getInstance(context)

// Check user consent
val hasConsent = privacyManager.hasUserConsent()

// Record user consent
val consent = PrivacyManager.UserConsent(
    privacyPolicyVersion = "1.0",
    consentGiven = true,
    analyticsConsent = false,
    crashReportingConsent = false,
    consentMethod = "dialog"
)
privacyManager.recordUserConsent(consent, "dialog")

// Update data collection setting
privacyManager.updateDataCollectionSetting(
    dataType = "audio_data",
    enabled = false,
    purpose = "speech recognition and translation"
)

// Get compliance report
val report = privacyManager.getPrivacyComplianceReport()
```

## Integration

### Quick Setup
Use the factory class for easy initialization:

```kotlin
// Initialize complete system
val components = context.initializePermissionSystem()

// Or use basic system for simple cases
val basicSystem = context.createBasicPermissionSystem()
```

### MainActivity Integration
See `PermissionIntegrationExample.kt` for complete integration examples:

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var permissionComponents: PermissionSystemFactory.PermissionSystemComponents
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize permission system
        lifecycleScope.launch {
            permissionComponents = initializePermissionSystem()
            
            // Check consent and permissions
            val setupResult = PermissionIntegrationExample.setupMainActivityPermissions(this@MainActivity, this@MainActivity)
            handlePermissionSetupResult(setupResult)
        }
    }
    
    private fun handlePermissionSetupResult(result: PermissionIntegrationExample.PermissionSetupResult) {
        when (result) {
            PermissionIntegrationExample.PermissionSetupResult.READY -> {
                // All permissions granted, proceed with app
            }
            PermissionIntegrationExample.PermissionSetupResult.CONSENT_REQUIRED -> {
                // Show privacy consent dialog
            }
            // ... handle other cases
        }
    }
}
```

## Permission Rationales

The system includes detailed rationales for all permissions:

### RECORD_AUDIO
- **Purpose**: Live translation, speech recognition, voice commands
- **Privacy**: Audio processed locally when possible, no permanent storage
- **Alternatives**: Manual text input, text-based translation only

### INTERNET
- **Purpose**: Online translation, language model updates, real-time translation
- **Privacy**: Encrypted communications, no personal data sharing
- **Alternatives**: Offline translation models

### POST_NOTIFICATIONS (Android 13+)
- **Purpose**: Translation status, service updates, quick actions
- **Privacy**: Status notifications only, no sensitive content
- **Alternatives**: Manual status checking, in-app indicators

## Recovery Strategies

The system uses intelligent recovery strategies:

1. **IMMEDIATE**: Request permission immediately (for essential permissions)
2. **DELAYED**: Wait for better context or user interaction
3. **USER_INITIATED**: Let user decide when to request
4. **GRACEFUL_DEGRADATION**: Continue with limited functionality
5. **DISABLE_FEATURE**: Disable feature requiring permission

## Privacy Compliance

The system provides comprehensive privacy compliance features:

- **Consent Management**: Track user consent with timestamps and methods
- **Data Collection Transparency**: Granular control over data collection
- **Privacy Event Logging**: Audit trail for compliance
- **Consent Withdrawal**: Easy consent withdrawal process
- **Compliance Reporting**: Generate privacy compliance reports

## Analytics and Monitoring

The system provides detailed analytics:

- Permission request statistics
- Recovery attempt tracking
- User behavior patterns
- Privacy compliance metrics
- Performance monitoring

## Best Practices

1. **Always check consent** before requesting permissions
2. **Show rationale** before requesting permissions
3. **Handle recovery** for revoked permissions
4. **Monitor permission states** for automatic recovery
5. **Respect user choices** and provide alternatives
6. **Maintain privacy compliance** with proper consent management

## Testing

The system includes comprehensive testing support:

- Mock permission states for testing
- Analytics data for testing scenarios
- Recovery simulation for edge cases
- Privacy compliance testing utilities

## Migration

When updating the permission system:

1. Check privacy policy version changes
2. Migrate user consent data if needed
3. Update permission rationale explanations
4. Test recovery mechanisms
5. Verify compliance reporting

## Support

For issues or questions about the permission management system, refer to:

- Integration examples in `PermissionIntegrationExample.kt`
- Factory class in `PermissionSystemFactory.kt`
- Individual component documentation in their respective files
