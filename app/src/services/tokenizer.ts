import { getNllbLanguageId, EOS_TOKEN_ID, NLLB_DICTIONARY_LENGTH } from '@/constants/nllb-languages';

/* SentencePiece space marker (U+2581) */
const SP_SPACE = '\u2581';

/* Byte fallback token pattern: <0xHH> */
const BYTE_FALLBACK_RE = /^<0x([0-9A-Fa-f]{2})>$/;

interface TokenizerState {
  vocab: Map<string, number>;
  idToToken: Map<number, string>;
  mergeRanks: Map<string, number>;
  hasByteFallback: boolean;
}

let state: TokenizerState | null = null;

/**
 * Load tokenizer from parsed tokenizer.json content.
 * @param json - Parsed JSON object from HuggingFace tokenizer.json
 */
export function loadTokenizer(json: Record<string, unknown>): void {
  if (state) return;

  const model = json['model'] as Record<string, unknown>;
  const rawVocab = model['vocab'] as Record<string, number>;
  const rawMerges = model['merges'] as string[];
  const hasByteFallback = (model['byte_fallback'] as boolean) ?? false;
  const addedTokens = (json['added_tokens'] as Array<{ id: number; content: string }>) ?? [];

  /* Build vocab map (token → ID) */
  const vocab = new Map<string, number>();
  for (const [token, id] of Object.entries(rawVocab)) {
    vocab.set(token, id);
  }
  for (const entry of addedTokens) {
    vocab.set(entry.content, entry.id);
  }

  /* Build reverse map (ID → token) */
  const idToToken = new Map<number, string>();
  for (const [token, id] of vocab) {
    idToToken.set(id, token);
  }

  /* Build merge rank map for O(1) pair priority lookup */
  const mergeRanks = new Map<string, number>();
  for (let i = 0; i < rawMerges.length; i++) {
    mergeRanks.set(rawMerges[i], i);
  }

  state = { vocab, idToToken, mergeRanks, hasByteFallback };
}

/** Encode raw text to BPE token IDs (no language tokens or EOS). */
export function encode(text: string): number[] {
  if (!state) throw new Error('Tokenizer not loaded');

  const normalized = SP_SPACE + text.replace(/ /g, SP_SPACE);
  let tokens = splitIntoInitialTokens(normalized);
  tokens = applyBpeMerges(tokens);

  return tokens.map((t) => {
    const id = state!.vocab.get(t);
    if (id !== undefined) return id;
    return state!.vocab.get('<unk>') ?? 3;
  });
}

/** Encode text in NLLB format: [src_lang_id, ...text_tokens, EOS] */
export function encodeForNllb(text: string, srcLangNllbCode: string): number[] {
  const textIds = encode(text);
  const langId = getNllbLanguageId(srcLangNllbCode);
  if (langId === -1) throw new Error(`Unknown NLLB language code: ${srcLangNllbCode}`);

  return [langId, ...textIds, EOS_TOKEN_ID];
}

/** Decode token IDs back to text, skipping special and language tokens. */
export function decode(ids: number[]): string {
  if (!state) throw new Error('Tokenizer not loaded');

  const pieces: string[] = [];
  for (const id of ids) {
    if (id <= 3) continue;
    if (id >= NLLB_DICTIONARY_LENGTH) continue;

    const token = state.idToToken.get(id);
    if (!token) continue;

    const byteMatch = BYTE_FALLBACK_RE.exec(token);
    if (byteMatch) {
      pieces.push(String.fromCharCode(parseInt(byteMatch[1], 16)));
      continue;
    }

    pieces.push(token);
  }

  return pieces.join('').replace(/\u2581/g, ' ').trimStart();
}

function splitIntoInitialTokens(text: string): string[] {
  const tokens: string[] = [];

  for (const char of text) {
    if (state!.vocab.has(char)) {
      tokens.push(char);
    } else if (state!.hasByteFallback) {
      const bytes = new TextEncoder().encode(char);
      for (const byte of bytes) {
        const hex = byte.toString(16).toUpperCase().padStart(2, '0');
        tokens.push(`<0x${hex}>`);
      }
    } else {
      tokens.push('<unk>');
    }
  }

  return tokens;
}

function applyBpeMerges(tokens: string[]): string[] {
  if (tokens.length <= 1) return tokens;

  /* eslint-disable no-constant-condition */
  while (true) {
    let bestRank = Infinity;
    let bestIdx = -1;

    for (let i = 0; i < tokens.length - 1; i++) {
      const pair = tokens[i] + ' ' + tokens[i + 1];
      const rank = state!.mergeRanks.get(pair);
      if (rank !== undefined && rank < bestRank) {
        bestRank = rank;
        bestIdx = i;
      }
    }

    if (bestIdx === -1) break;

    const merged = tokens[bestIdx] + tokens[bestIdx + 1];
    tokens.splice(bestIdx, 2, merged);
  }

  return tokens;
}

export function isTokenizerLoaded(): boolean {
  return state !== null;
}

export function releaseTokenizer(): void {
  state = null;
}
