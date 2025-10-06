#!/bin/bash

# TTS Testing Helper Scripts

echo "🔧 TTS Testing Helper"
echo "====================="

# Function to check if device is connected
check_device() {
    if ! command -v adb &> /dev/null; then
        echo "❌ ADB not found. Please install Android SDK tools."
        exit 1
    fi
    
    if [ -z "$(adb devices | grep -v 'List of devices' | grep device)" ]; then
        echo "❌ No Android device/emulator connected."
        echo "💡 Start an emulator or connect a device and try again."
        exit 1
    fi
    
    echo "✅ Android device connected: $(adb devices | grep device | head -1 | cut -f1)"
}

# Function to build and install app
build_and_install() {
    echo "🔨 Building debug APK..."
    ./gradlew assembleDebug
    
    if [ $? -eq 0 ]; then
        echo "📱 Installing app on device..."
        adb install -r app/build/outputs/apk/debug/app-debug.apk
        
        if [ $? -eq 0 ]; then
            echo "✅ App installed successfully!"
        else
            echo "❌ Failed to install app"
            exit 1
        fi
    else
        echo "❌ Build failed"
        exit 1
    fi
}

# Function to run instrumented tests
run_instrumented_tests() {
    echo "🧪 Running TTS instrumented tests..."
    ./gradlew tts:connectedAndroidTest
}

# Function to monitor TTS logs
monitor_logs() {
    echo "📝 Monitoring TTS logs (Ctrl+C to stop)..."
    adb logcat -c  # Clear existing logs
    adb logcat -v time | grep -E "(TTS|TextToSpeech|tts_debug|TTSService)"
}

# Function to check TTS system settings
check_tts_system() {
    echo "🔍 Checking TTS system configuration..."
    
    echo "Current TTS engine:"
    adb shell settings get secure tts_default_synth
    
    echo -e "\nInstalled TTS packages:"
    adb shell pm list packages | grep -i tts
    
    echo -e "\nAudio volume levels:"
    adb shell media volume --show
    
    echo -e "\nTTS engine status:"
    adb shell dumpsys media_session | grep -i tts || echo "No active TTS sessions"
}

# Function to launch debug activity
launch_debug_activity() {
    echo "🚀 Launching TTS debug activity..."
    adb shell am start -n com.example.gloabtranslate/.debug.TTSTestActivity
}

# Function to setup TTS prerequisites
setup_tts() {
    echo "⚙️  Setting up TTS prerequisites..."
    
    # Ensure audio is enabled
    adb shell media volume --set 7
    
    # Wake up device
    adb shell input keyevent KEYCODE_WAKEUP
    
    # Unlock if needed (swipe up)
    adb shell input swipe 300 1000 300 500
    
    echo "✅ Basic TTS setup complete"
}

# Main menu
show_menu() {
    echo ""
    echo "Choose an option:"
    echo "1) Build and install app"
    echo "2) Run instrumented tests"  
    echo "3) Launch debug activity"
    echo "4) Monitor TTS logs"
    echo "5) Check TTS system settings"
    echo "6) Setup TTS prerequisites"
    echo "7) Full test workflow"
    echo "8) Exit"
    echo ""
    read -p "Enter choice [1-8]: " choice
    
    case $choice in
        1) build_and_install ;;
        2) run_instrumented_tests ;;
        3) launch_debug_activity ;;
        4) monitor_logs ;;
        5) check_tts_system ;;
        6) setup_tts ;;
        7) full_workflow ;;
        8) echo "👋 Goodbye!"; exit 0 ;;
        *) echo "❌ Invalid option"; show_menu ;;
    esac
    
    echo ""
    read -p "Press Enter to continue..."
    show_menu
}

# Full workflow
full_workflow() {
    echo "🔄 Running full TTS test workflow..."
    check_device
    setup_tts
    build_and_install
    
    echo "🧪 Would you like to run instrumented tests? (y/n)"
    read -p "> " run_tests
    
    if [[ $run_tests =~ ^[Yy]$ ]]; then
        run_instrumented_tests
    fi
    
    echo "🚀 Would you like to launch debug activity? (y/n)"
    read -p "> " launch_debug
    
    if [[ $launch_debug =~ ^[Yy]$ ]]; then
        launch_debug_activity
        
        echo "📝 Would you like to monitor logs? (y/n)"
        read -p "> " monitor
        
        if [[ $monitor =~ ^[Yy]$ ]]; then
            monitor_logs
        fi
    fi
}

# Start the script
check_device
show_menu