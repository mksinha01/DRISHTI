# Emergency Alert Flow & Protocols

Safety is the highest priority in Drishti. The Emergency system is designed with multiple safeguards, cancellation windows, and multi-channel dispatch fallbacks.

## Triggers

1. **Voice Emergency**:
   - Utterance matches: `"Help, emergency!"`, `"Save me"`, or `"Help!"`
   - Countdown: 10 seconds with spoken updates every 2 seconds.

2. **Fall Detection**:
   - Accelerometer detects impact spike (> 2.5g) followed by stillness (< 0.2g for 3s).
   - Prompts: "Are you okay? Say cancel to stop emergency alert."
   - Countdown: 15 seconds.

3. **Inactivity Timeout**:
   - No voice interaction for 60 minutes while in unfamiliar location.
   - Countdown: 30 seconds.

## Cancellation Window
At any point during the countdown, the user can speak `"Cancel"`, `"Stop"`, or `"I'm fine"` to immediately abort the emergency state and restore the previous operating mode.

## Dispatch Order
1. **SMS via SmsManager**: Direct text sent to emergency contacts with real-time GPS coordinates and Google Maps link:
   `"EMERGENCY: Drishti user needs assistance. Current location: https://maps.google.com/?q=28.6139,77.2090"`
2. **WhatsApp Intent**: Automated message draft to emergency WhatsApp contacts.
3. **Phone Dialer**: Automatically dials primary emergency contact via `Intent.ACTION_CALL`.
4. **Local Siren**: If cellular connectivity is absent, sounds high-decibel local siren via phone speaker.
