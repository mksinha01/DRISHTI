# Drishti ESP32-CAM Firmware

This directory contains the firmware for the external ESP32-CAM video capture module used by Drishti.

## Features
- Hardware JPEG capture using OV2640 sensor.
- HTTP MJPEG stream at `http://<device-ip>/stream`.
- Diagnostic status endpoint at `http://<device-ip>/status`.
- Automatic Wi-Fi reconnection handling.
- Tuned for VGA (640x480) with PSRAM enabled for low latency (~100ms capture).

## Hardware Setup
- Board: AI-Thinker ESP32-CAM
- Module: FTDI USB-to-Serial Programmer (Set to 3.3V, connect GPIO 0 to GND during flashing).

## Flashing Instructions
1. Open `mjpeg_streamer/mjpeg_streamer.ino` in Arduino IDE.
2. Under **Tools > Board**, select **AI Thinker ESP32-CAM**.
3. Under **Tools > Flash Frequency**, select **80MHz**.
4. Under **Tools > Partition Scheme**, select **Huge APP (3MB No OTA/1MB SPIFFS)**.
5. In `config.h`, set your phone's Wi-Fi hotspot SSID and password:
   ```c
   #define DEFAULT_WIFI_SSID     "Drishti_Hotspot"
   #define DEFAULT_WIFI_PASS     "drishti1234"
   ```
6. Disconnect GPIO 0 from GND and hit the Reset button.
7. Note the IP printed on the Serial Monitor (`115200` baud) and configure it in the Drishti Android App settings.
