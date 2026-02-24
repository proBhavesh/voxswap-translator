/* Kokoro IPA vocabulary — maps each symbol to a token ID */
const VOCAB: Record<string, number> = (() => {
  const pad = '$';
  const punctuation = ';:,.!?¡¿—…"«»"" ';
  const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
  const ipa =
    'ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘\u0027̩\u2019ᵻ';

  const symbols = [pad, ...punctuation.split(''), ...letters.split(''), ...ipa.split('')];
  const map: Record<string, number> = {};
  for (let i = 0; i < symbols.length; i++) {
    map[symbols[i]] = i;
  }
  return map;
})();

/* English digraph → IPA mappings */
const EN_DIGRAPHS: Record<string, string> = {
  th: 'θ',
  sh: 'ʃ',
  ch: 'tʃ',
  ng: 'ŋ',
  er: 'ɝ',
  ar: 'ɑɹ',
  or: 'ɔɹ',
  ir: 'ɪɹ',
  ur: 'ʊɹ',
};

/* English single char → IPA */
const EN_CHARS: Record<string, string> = {
  a: 'ə',
  e: 'ɛ',
  i: 'ɪ',
  o: 'oʊ',
  u: 'ʌ',
  j: 'dʒ',
  r: 'ɹ',
};

/* Common English words → IPA (avoids bad rule-based output) */
const EN_WORDS: Record<string, string> = {
  hello: 'hɛˈloʊ',
  world: 'wˈɝld',
  this: 'ðˈɪs',
  is: 'ˈɪz',
  a: 'ə',
  test: 'tˈɛst',
  of: 'ʌv',
  the: 'ðə',
  to: 'tˈuː',
  and: 'ˈænd',
  in: 'ˈɪn',
  for: 'fˈɔɹ',
  not: 'nˈɑːt',
  you: 'jˈuː',
  it: 'ˈɪt',
  with: 'wˈɪð',
  are: 'ˈɑːɹ',
  was: 'wˈɑːz',
  that: 'ðˈæt',
  have: 'hˈæv',
  from: 'fɹˈʌm',
  they: 'ðˈeɪ',
  we: 'wˈiː',
  can: 'kˈæn',
  there: 'ðˈɛɹ',
  what: 'wˈʌt',
  been: 'bˈɪn',
  one: 'wˈʌn',
  please: 'plˈiːz',
  thank: 'θˈæŋk',
  good: 'ɡˈʊd',
  yes: 'jˈɛs',
  no: 'nˈoʊ',
};

const IPA_VOWELS = /[ɑɐɒæəɘɚɛɜɝɞɨɪʊʌɔoeiuaɑː]/;

function normalizeText(text: string): string {
  return text
    .trim()
    .replace(/\s+/g, ' ')
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/[\u201C\u201D]/g, '"')
    .replace(/…/g, '...');
}

function phonemizeEnglishWord(word: string): string {
  const lower = word.toLowerCase().replace(/[.,!?;:'"]/g, '');
  if (EN_WORDS[lower]) return EN_WORDS[lower];

  let phonemes = '';
  let i = 0;

  while (i < word.length) {
    if (i < word.length - 1) {
      const digraph = word.substring(i, i + 2).toLowerCase();
      if (EN_DIGRAPHS[digraph]) {
        phonemes += EN_DIGRAPHS[digraph];
        i += 2;
        continue;
      }
    }

    const char = word[i].toLowerCase();
    if (EN_CHARS[char]) {
      phonemes += EN_CHARS[char];
    } else if (/[a-z]/.test(char)) {
      phonemes += char;
    } else if (/[.,!?;:'"-]/.test(char)) {
      phonemes += char;
    }
    i++;
  }

  /* Add stress on first vowel for longer words */
  if (phonemes.length > 2 && !/[.,!?;:'"]/g.test(phonemes)) {
    const match = IPA_VOWELS.exec(phonemes);
    if (match?.index !== undefined) {
      phonemes = phonemes.substring(0, match.index) + 'ˈ' + phonemes.substring(match.index);
    }
  }

  return phonemes;
}

/** Convert text to IPA phonemes. Currently English-focused, extensible per language. */
export function phonemize(text: string, _language?: string): string {
  const normalized = normalizeText(text);
  const words = normalized.split(/\s+/);

  /* TODO: Add language-specific phonemization (French, Spanish, etc.) */
  const phonemized = words.map(phonemizeEnglishWord);
  return phonemized.join(' ');
}

/** Tokenize IPA phonemes into Kokoro token IDs. */
export function tokenizePhonemes(text: string): number[] {
  /* Auto-detect: if text contains IPA characters, treat as pre-phonemized */
  const isAlreadyPhonemes = /[ɑɐɒæəɘɚɛɜɝɞɨɪʊʌɔˈˌː]/.test(text);
  const phonemes = isAlreadyPhonemes ? text : phonemize(text);

  const tokens: number[] = [0]; /* start token */

  for (const char of phonemes) {
    const id = VOCAB[char];
    if (id !== undefined) {
      tokens.push(id);
    }
  }

  tokens.push(0); /* end token */
  return tokens;
}

export const KOKORO_STYLE_DIM = 256;
export const KOKORO_MAX_PHONEMES = 510;
