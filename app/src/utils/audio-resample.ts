/**
 * Resample PCM audio using linear interpolation.
 * Works with both Int16Array (16-bit PCM) and Float32Array.
 */
export function resampleLinear(
  input: Int16Array | Float32Array,
  fromRate: number,
  toRate: number,
): Int16Array | Float32Array {
  if (fromRate === toRate) return input;

  const ratio = fromRate / toRate;
  const outputLength = Math.ceil(input.length / ratio);
  const isInt16 = input instanceof Int16Array;
  const output = isInt16 ? new Int16Array(outputLength) : new Float32Array(outputLength);

  for (let i = 0; i < outputLength; i++) {
    const srcIdx = i * ratio;
    const lo = Math.floor(srcIdx);
    const hi = Math.min(lo + 1, input.length - 1);
    const frac = srcIdx - lo;

    output[i] = Math.round(input[lo] * (1 - frac) + input[hi] * frac);
  }

  return output;
}
