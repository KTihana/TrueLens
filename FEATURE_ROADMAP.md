# TrueLens — Ultimate Camera App Feature Roadmap & Checklist

A living master list of every feature built, verified, and planned for **TrueLens** (Zero-AI, true-optics Android camera app).

---

## Completed & Verified (Built in App)

### Core Zero-AI Capture & Optics
- [x] **Zero-AI Pure Sensor Pipeline** — Disables OEM edge sharpening, face beauty triggers, scene AI, and aggressive noise smearing (`TrueLensCameraEngine.kt`)
- [x] **12/14-bit Bayer RAW (`.dng`) + Clean/Graded JPEG** — Dual-stream `RAW+JPG`, `RAW`, and `CLEAN` capture saved to `Pictures/TrueLens`
- [x] **True Light Auto Optical Metering** — Uses ambient light sensor (lux) + live 64-bin histogram to adapt hardware exposure to outside light (`SUNLIGHT`, `DAYLIGHT`, `SHADE`, `INDOOR`, `LOW LIGHT`) without AI filters (`TrueLightAutoMeter.kt`)
- [x] **Front & Back Camera Support + 1-Tap Flip** — Multi-lens selector (`0.6x`, `1x`, `2x`, `Front`) + dedicated flip button
- [x] **Tap-to-Focus + On-Screen Exposure Sun Slider + AF/AE Lock** — Canon-style corner brackets + instant EV slider + lock
- [x] **Pinch-to-Zoom** (`1.0x–8.0x`) on the live viewfinder
- [x] **Hardware Volume-Key Shutter**

### Canon-Style Manual Controls (`PRO`)
- [x] **Exposure Mode Dial (`P` / `Tv` / `M`)** — Program Auto, Shutter Priority, Full Manual
- [x] **Manual ISO, Shutter Speed, EV Compensation, Focus (Diopters/Meters), and Kelvin White Balance (`2500K–9500K`)**
- [x] **Pro Overlays** — Live 64-bin Histogram, GPU Focus Peaking, GPU Zebra Highlight Warnings (>96%), Dual-Axis Horizon Level, and `3×3` / Golden Ratio / Crosshair Grids

### Live Lightroom Engine (`GRADE`) & Darkroom
- [x] **60fps OpenGL ES Live Grading Shader** — Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Temp, Tint, Vibrance, Saturation, Clarity, Optical Vignette, Organic Film Grain (`LiveGradingRenderer.kt`)
- [x] **7 Built-In Film & Natural Presets** — True Zero, Canon Natural, Portra 400, Classic Chrome, Monochrom, Golden Hour, Cool Cinema
- [x] **Hold-to-Compare Original** on both live viewfinder and Darkroom
- [x] **Built-In Post-Capture Darkroom Editor** with EXIF readout and graded export (`DarkroomScreen.kt`)
- [x] **Full In-App TrueLens Gallery** — Browse all captured TrueLens photos & panoramas and open any in Darkroom (`GalleryScreen.kt`)

### Pose Overlay & Pose Inspos Library (`POSE`)
- [x] **Live Pose Overlay on Both Front & Back Cameras** (`PoseOverlayLayer.kt`)
- [x] **3 Blend Modes** — Ghost Photo, **Offline ML Kit Subject Cutout**, and **Neon Outline** (`PoseOverlayProcessor.kt`)
- [x] **Full Multi-Touch Control** — Two-finger pinch-to-zoom (`0.3x–4.0x`), drag-to-move, rotate, horizontal mirror, opacity slider (`5%–100%`), lock/unlock, hide/show (for both full photos and cutouts)
- [x] **Pose Inspos Folder Library** — Custom folders + Gallery multi-import (`PoseInsposScreen.kt`, `PoseInspoRepository.kt`)
- [x] **Android Share Sheet Receiver** — `Share → TrueLens` directly from Pinterest, Instagram, Chrome, or Gallery
- [x] **Pinterest Importer** — Zero-login Public Board / Pin URL import + Official Pinterest API v5 OAuth/Token sync for Private & Secret boards (`PinterestImporter.kt`)

### Phase 2 Shooting Modes & Usability
- [x] **Gyro-Guided Panorama (`PANO`)** — Locked exposure/WB, auto yaw capture + manual `+ FRAME`, feathered overlap stitching (`PanoramaStitcher.kt`)
- [x] **Time-Lapse Intervalometer (`TIMELAPSE`)** — Configurable interval (`1s–30s`) & frame count (`15–120 / ∞`) with locked exposure (`TimeLapseController.kt`)
- [x] **Super Macro (`MACRO`) + GPU 4x Focus Magnifier Loupe** — Close-focus lock + Focus Peaking + live circular 4x magnifier
- [x] **Self-Timer (`OFF / 3s / 5s / 10s`)** — Centered viewfinder countdown overlay
- [x] **Friend Mode (`FRIEND`)** — 1-tap simplified UI hiding pro dials so anyone can align your Pose Cutout and snap the shot
- [x] **Custom TrueLens App Icon** — Matte obsidian & Canon-amber aperture lens vector emblem

---

## Up Next (Planned Feature Backlog)

### High Priority — Framing, Speed & Custom Presets
- [x] **DSLR Aspect Ratio Framing Masks** — `4:3` (Full Sensor), `3:2` (Canon DSLR), `4:5` (Instagram Portrait), `16:9` (Cinema), `65:24` (XPan Panoramic) with darkroom viewfinder mattes while still saving the full uncropped RAW negative
- [x] **Curated Aesthetic Presets + Save Your Own Custom Lightroom Presets** — Researched top film & editorial looks (*Reggie's Portra*, *Classic Cuban Neg*, *Kodak Gold 200*, *CineStill 800T*, *Classic Chrome*, *Editorial Clean*, *Dark Academia*, *Ilford HP5 400*) + persistent `+ SAVE` custom recipe builder
- [x] **Hands-Free Solo Burst** — Pair Self-Timer with `1× / 3× / 5×` rapid shots so you can shift poses slightly without walking back to the phone
- [x] **Darkroom Quick Gallery Filmstrip** — Horizontal thumbnail filmstrip along the bottom of the Darkroom screen to swipe through recent shots instantly
- [x] **Canon Custom Mode Presets (`C1 / C2 / C3`) & Auto Exposure Bracketing (`AEB`)** — Save/recall Canon `C1/C2/C3` manual setups + 3-shot `-1/0/+1` or `-2/0/+2 EV` bracketed HDR bursts
- [x] **Pro Video (`VIDEO`) & High-Speed Slow Motion (`SLOW-MO`)** — `120fps`/`240fps` hardware slow-mo (with graceful fallback) + MP4 saving to `Movies/TrueLens`
- [x] **Universal "Super Steady" Gyro Video Stabilizer** — Works on any Android phone using high-rate gyroscope shake-cancellation + OpenGL overscan smoothing + hardware OIS/EIS
- [x] **Document Scanner (`SCAN`)** — Live document alignment frame, adaptive Color Doc & Crisp B&W enhancement, exports real **PDF** (`Documents/TrueLens`) + JPEG
- [x] **Stop Motion with Onion-Skinning (`STOP-MO`)** — Live semi-transparent last-frame overlay with opacity slider (`5%–95%`), hide/show toggle, `UNDO`, `6/12/15/24 fps` selector, and hardware H.264 `.mp4` export to `Movies/TrueLens`
- [x] **`.cube` 3D LUT Import** — Import desktop Lightroom / DaVinci Resolve `.cube` 3D LUT files (`CubeLutParser.kt`) for grading & Darkroom export
- [x] **Front-Camera Mirror-Save Toggle** — Keep preview naturally mirrored while choosing whether saved selfies stay mirrored (`MIRROR`) or flip to true-to-life orientation (`TRUE`)

### Pro Camera & Lightroom Power Upgrades (Completed)
- [x] **True Manual Long Exposure / Bulb Mode (`BULB`)** — Multi-frame optical light accumulation (`2s / 4s / 8s / 15s`, `Silky Motion` & `Light Trails`) (`LongExposureEngine.kt`)
- [x] **Live Split-Screen Before/After Divider (`SPLIT`)** — Swipeable vertical split line on the live viewfinder comparing untouched sensor (left) vs. live Lightroom grade (right)
- [x] **RGB Tone Curves & HSL Color Mixer (`HSL` Tab)** — Fine-tune Red Sat, Skin Luminance, Green Foliage Sat, Sky Sat/Depth, and Shadows/Midtones/Highlights Tone Curve live in OpenGL + Darkroom
- [x] **Time-Lapse MP4 Video Compiler** — Automatically compiles intervalometer frames into a smooth 24fps H.264 MP4 in `Movies/TrueLens`
- [x] **Super Macro Focus Stacking Burst** — Automatically sweeps focus across 5 focal planes and merges the sharpest pixels per plane via Laplacian sharpness (`FocusStacker.kt`)
- [x] **Optical Perspective / Keystone Straightener** — Pure geometric vertical tilt & rotation straightening in Darkroom (`V-TILT` & `ROT` sliders, zero AI distortion)
