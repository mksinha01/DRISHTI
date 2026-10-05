# Drishti Models Directory

This directory holds on-device TFLite models for Drishti:
1. `yolov8n_float16.tflite` - Quantized YOLOv8n model for real-time obstacle and object detection.
2. `mobilenet_face_embedding.tflite` - Lightweight embedding model for face recognition.

## Generating the YOLOv8n TFLite model:
You can export YOLOv8n to TFLite format using `ultralytics`:
```bash
pip install ultralytics
python -c "from ultralytics import YOLO; model = YOLO('yolov8n.pt'); model.export(format='tflite', half=True)"
```
Then copy the exported `.tflite` file into this directory and `android/app/src/main/assets/`.
