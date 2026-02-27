# Streaming Translation for Conference Use Case

## Problem

The current translation pipeline is **utterance-based**: it waits for the speaker to pause (1.3s silence timeout) before processing anything. This fails for conferences where:

- A speaker talks continuously for 30-60 minutes with few natural pauses
- The 29-second hard cap cuts audio mid-sentence, producing bad translations
- Listeners experience long delays (silence → big chunk of translated audio → silence)
- The pipeline is fully serial: mic stops during STT + translate + TTS

## Goal

Produce a steady stream of translated sentences with ~5-8 second delay behind the speaker, running indefinitely.

## Current Architecture (Utterance-Based)

```
Mic → Recorder (amplitude VAD, waits for 1.3s silence) → delivers full utterance
    → Whisper STT (entire utterance at once)
    → NLLB translate to target 1 (waits for STT to finish)
    → NLLB translate to target 2 (waits for target 1 to finish)
    → TTS (speaks both translations)
    → Mic restarts
```

Key files:
- `Recorder.java` — audio capture + amplitude-based VAD + circular buffer
- `WalkieTalkieService.java` — orchestrates the pipeline
- `Recognizer.java` — Whisper ONNX inference (6 sessions)
- `Translator.java` — NLLB-200 ONNX inference (4 sessions)

### Problems for Conferences

| Issue | Detail |
|-------|--------|
| VAD is amplitude-only | Background noise (crowd, AC) triggers false positives. No real speech detection. |
| 1.3s silence timeout | Speaker must pause 1.3s before anything is processed. Feels sluggish. |
| 29s hard cap | Continuous speech force-cut at 29s, often mid-sentence. Bad translation quality. |
| Fully serial pipeline | Mic → STT → Translate → TTS runs sequentially. Mic off during processing. |
| Sequential target languages | Target 1 finishes before target 2 starts. Doubles translation latency. |
| No partial output | Nothing reaches the listener until the entire pipeline completes for a chunk. |
| System TTS variability | Quality depends on device TTS engine. Some languages missing entirely. |

## Proposed Architecture (Chunked Streaming)

```
Mic → Fixed 5s audio chunks (no VAD dependency)
    → Whisper STT (returns timestamped sentence segments)
    → Sentence accumulator (buffers incomplete sentences across chunks)
    → Complete sentence → NLLB target 1 + target 2 (parallel)
    → TTS / UDP audio stream
    → (Mic never stops, next chunk already recording)
```

### 1. Fixed-Interval Audio Chunking

Replace silence-based VAD with fixed time windows. Send audio to Whisper every ~5 seconds regardless of whether the speaker paused.

```
Audio: [----5s----][----5s----][----5s----][----5s----]
         chunk 1     chunk 2     chunk 3     chunk 4
```

- Recorder runs continuously, never stops
- Every 5s, copy the buffer contents and send to Whisper
- Overlap chunks by ~1 second for context at boundaries
- No hard cap — runs indefinitely

VAD still useful as a secondary signal: skip sending empty/silent chunks to save CPU.

### 2. Whisper Returns Timestamped Segments

Whisper naturally segments audio into sentences with timestamps. A 5s chunk might produce:

```
Chunk 1 (0-5s):
  [0.0-3.2] "Welcome everyone to today's conference."
  [3.2-5.0] "Today we'll be discussing"               ← incomplete (no period)

Chunk 2 (5-10s):
  [0.0-2.8] "climate change and its impact."
  [2.8-5.0] "First, let me show you"                  ← incomplete
```

Note: Current Whisper integration (`Recognizer.java`) strips timestamps and returns a single text string. Need to modify to preserve segment boundaries — either use Whisper's timestamp tokens or detect sentence boundaries in the output text.

### 3. Sentence Accumulator

Sits between Whisper output and the translator. Buffers incomplete text, combines across chunks.

```
State: buffer = ""

Chunk 1 output:
  "Welcome everyone to today's conference." → ends with period → TRANSLATE NOW
  "Today we'll be discussing"               → no period → buffer = "Today we'll be discussing"

Chunk 2 output:
  "climate change and its impact."          → prepend buffer → TRANSLATE: "Today we'll be discussing climate change and its impact."
  "First, let me show you"                  → no period → buffer = "First, let me show you"
```

Sentence boundary detection:
- Period, question mark, exclamation mark (primary)
- Handle abbreviations: "Dr.", "Mr.", "U.S." — minimum sentence length heuristic (skip boundaries in first 3 words)
- Handle non-Latin punctuation: `。` (CJK), `।` (Hindi), `။` (Myanmar)
- Flush buffer on silence (fall back to VAD signal) even without punctuation
- Flush buffer if it exceeds ~20 seconds of accumulated text (safety cap)

### 4. Pipeline Parallelism

Overlap recording, STT, and translation of different chunks:

```
Time 0-5s:   [Record chunk 1]
Time 5-7s:   [Record chunk 2]  [STT chunk 1.............]
Time 7-8s:   [Record chunk 2]  [Translate sentence 1....] [STT done]
Time 8-9s:   [Record chunk 2]  [TTS sentence 1..........] [Translate done]
Time 9s:     [Speaker output]  [STT chunk 2 starts......]
Time 10-15s: [Record chunk 3]  [STT chunk 2.............]
```

Key: while NLLB translates sentence N, the Recorder is filling the buffer for chunk N+1 (I/O thread, not CPU-bound). True CPU parallelism between Whisper and NLLB isn't possible on single-threaded ONNX, but the overlap comes from recording happening on a separate thread.

### 5. Parallel Target Language Translation

Current code chains target 1 → then target 2:

```java
/* WalkieTalkieService.java — current sequential approach */
target1TranslateListener = new Translator.TranslateListener() {
    public void onTranslatedText(...) {
        // ... handle target 1 result ...
        if (isFinal && targetLanguage2 != null) {
            translateToTarget2(textToTranslate);  // starts only after target 1 completes
        }
    }
};
```

Better: launch both translations immediately since they're independent:

```java
/* Fire both translations at once */
translator.translate(text, source, target1, beamSize, false, target1Listener);
if (target2 != null) {
    translator.translate(text, source, target2, beamSize, false, target2Listener);
}
```

Note: `Translator.translate()` uses an internal queue (`ArrayDeque`), so they'll still execute sequentially on the ONNX thread. But this removes the unnecessary wait for target 1's callback before even queuing target 2. The two translations run back-to-back instead of callback-chained.

### 6. TTS Queue Management

For conferences, translated sentences arrive faster than TTS can speak them. Need queue management:

- FIFO queue of sentences to speak
- If queue grows beyond N sentences (e.g., 3), drop oldest unsent sentences
- This keeps the listener close to real-time rather than falling further behind
- Log dropped sentences for debugging

For VoxSwap's final architecture (UDP to box → Auracast), TTS happens on the phone for preview only. The box receives PCM audio and broadcasts directly. But during development with the current app, system TTS is the output.

## Example: Conference Speaker (20 seconds)

Speaker says continuously:

> "Welcome to today's conference on artificial intelligence. We have three speakers lined up. The first talk will cover large language models and their applications in healthcare. Let's get started."

```
0-5s    Record chunk 1
        → Whisper: "Welcome to today's conference on artificial intelligence."
                   "We have three speakers"  [incomplete, buffered]
        → Translate: "Bienvenue à la conférence..." (French)
        → Translate: "Bienvenidos a la conferencia..." (Spanish) [parallel]

5-10s   Record chunk 2
        → Whisper: "lined up."
                   "The first talk will cover large language models and their" [buffered]
        → Accumulator completes: "We have three speakers lined up." → Translate
        → Meanwhile: listener hears French sentence 1

10-15s  Record chunk 3
        → Whisper: "applications in healthcare."
                   "Let's get started." [complete]
        → Accumulator completes: "The first talk will cover large language models and their applications in healthcare." → Translate
        → "Let's get started." → Translate
        → Meanwhile: listener hears sentence 2

15-20s  Listener hears sentences 3 and 4
```

Listener is ~5-8 seconds behind the speaker. Steady output, no long gaps.

## Technical Challenges

### Whisper Context Across Chunks

Each 5s chunk is processed independently — Whisper has no memory of previous chunks. The start of a sentence split at a chunk boundary may be misheard without preceding context.

Mitigation: overlap chunks by ~1 second. Feed Whisper 6s of audio (1s overlap + 5s new), but only use the text from the new 5s portion. The overlap gives Whisper acoustic context for the transition. Need to deduplicate any repeated text from the overlap region.

### Sentence Detection Accuracy

Whisper doesn't always punctuate correctly:
- "Dr. Smith" looks like a sentence boundary
- Numbers with periods: "Figure 3.2 shows..."
- Speaker trailing off without clear punctuation

Heuristics needed:
- Minimum sentence length (skip periods within first 15 characters)
- Abbreviation allowlist (Dr., Mr., Mrs., vs., etc., Fig., No.)
- If no sentence boundary detected in 15+ seconds, flush buffer anyway with best-guess split

### Single-Threaded ONNX on Mobile

Can't run Whisper and NLLB simultaneously on CPU. True parallelism would need:
- NPU for one model, CPU for the other (iMX8M Plus has 2.8 TOPS NPU, but phone inference is on CPU)
- Or: use the phone's GPU/NNAPI delegate for one model

In practice, pipeline parallelism (recording overlaps with inference) is the main win. The models still run sequentially on the CPU thread.

### Memory Pressure

Current setup already uses ~1.7GB RAM for both models loaded. Streaming means:
- Recorder buffer stays allocated permanently (circular buffer, ~480KB)
- Sentence accumulator adds minimal overhead (just a string buffer)
- No increase in model memory — same models, same sessions

The main concern is ONNX intermediate tensors from frequent inference calls. The existing `result.close()` pattern handles this, but worth monitoring for leaks during long sessions.

### Battery and Thermal for Long Sessions

30-60 minute continuous inference is heavy:
- Current VAD-gated approach: models idle during silence (saves battery)
- Streaming approach: Whisper runs every 5 seconds regardless (higher CPU usage)
- Mitigation: skip Whisper for silent chunks (keep amplitude check as a gate)
- Recommend plugged-in operation for conference use
- Monitor thermal throttling — if the phone throttles, inference time increases, pipeline falls behind

## What Changes in Code

| Component | File | Current | Streaming |
|-----------|------|---------|-----------|
| Audio chunking | `Recorder.java` | VAD-based, delivers on silence | Fixed 5s timer, delivers continuously |
| STT interface | `Recognizer.java` | Returns single text string | Returns sentence segments (or we split post-hoc) |
| Sentence buffer | New class | Doesn't exist | Accumulates incomplete sentences across chunks |
| Translation trigger | `WalkieTalkieService.java` | On Whisper callback | On sentence accumulator output |
| Target 2 timing | `WalkieTalkieService.java` | Chained after target 1 callback | Queued immediately with target 1 |
| Pipeline overlap | `WalkieTalkieService.java` | Mic stops during processing | Mic never stops |
| Max duration | `Recorder.java` | 29s hard cap | No limit |
| TTS output | `VoiceTranslationService.java` | Direct speak() | Queue with drop-oldest policy |

## Implementation Order

1. **Parallel target languages** — smallest change, immediate latency improvement. Just fire both translations without waiting.
2. **Keep mic running during processing** — remove the stop-mic-during-STT behavior for conference mode. Already partially done (`shouldDeactivateMicDuringTTS()` returns false in WalkieTalkieService).
3. **Fixed-interval chunking** — add a timer-based mode to Recorder that delivers audio every 5s instead of waiting for silence. Keep VAD mode as fallback for short conversations.
4. **Sentence accumulator** — new class that buffers Whisper output and emits complete sentences.
5. **Chunk overlap** — add 1s overlap to improve Whisper accuracy at boundaries.
6. **TTS queue management** — prevent listener from falling behind during long sessions.
7. **Testing with real conference audio** — record a 30-minute talk, run through the pipeline, measure latency and accuracy.

## Open Questions

- **Chunk duration**: 5s is a starting point. Shorter (3s) = lower latency but worse Whisper accuracy. Longer (8s) = better accuracy but more delay. Need to test.
- **Whisper timestamp tokens**: Does the current ONNX Whisper model support timestamp output? The code strips timestamps (`correctText()` removes `<|0.00|>` patterns). May need to re-enable or use a different approach.
- **Sentence splitting strategy**: Split in Whisper (using timestamp tokens) vs split post-hoc (using punctuation in text output)? Post-hoc is simpler but less accurate.
- **Conference mode toggle**: Should this be a separate mode in the UI, or should the app auto-detect long speech and switch strategies?
- **Multiple speakers**: In a panel discussion, speakers alternate rapidly. The 5s fixed window works, but speaker changes mid-chunk could confuse Whisper. Consider voice activity detection per speaker (speaker diarization) as a future enhancement.
