# F1 (GBIDGA+ML-Karthika-Normal) glyph name -> Unicode, from visual chart reading
# Uncertain entries marked with #?
N2U = {
    'space': ' ',
    'quotedbl': '\u0027',      # renders as '
    'numbersign': '\u25cc',    # dashed circle - UNKNOWN, placeholder
    'ampersand': '&',
    'quotesingle': '\u0027',
    'parenleft': '(',
    'parenright': ')',
    'comma': ',',
    'hyphen': '-',
    'period': '.',
    'slash': '/',
    'zero': '0', 'one': '1', 'two': '2', 'three': '3', 'four': '4',
    'five': '5', 'six': '6', 'seven': '7', 'eight': '8', 'nine': '9',
    'colon': ':', 'question': '?',
    # vowels
    'A': '\u0d05', 'B': '\u0d06', 'C': '\u0d07', 'D': '\u0d09',
    'F': '\u0d0e', 'G': '\u0d0f', 'H': '\u0d12',
    # consonants
    'I': '\u0d15', 'J': '\u0d16', 'K': '\u0d17', 'L': '\u0d18',
    'M': '\u0d19', 'N': '\u0d1a', 'P': '\u0d1b', 'R': '\u0d1e',
    'S': 'S', 'T': 'O',
    'U': '\u0d21', 'W': '\u0d23', 'X': '\u0d24', 'Y': '\u0d25',
    'Z': '\u0d26', 'bracketleft': '\u0d27', 'backslash': '\u0d28',
    'bracketright': '\u0d2a', 'asciicircum': '\u0d2b',
    'underscore': '\u0d2c', 'grave': '\u0d2d', 'a': '\u0d2e',
    'b': '\u0d2f', 'c': '\u0d30', 'd': '\u0d31', 'e': '\u0d32',
    'f': '\u0d33', 'g': '\u0d34', 'h': '\u0d35', 'i': '\u0d36',
    'j': '\u0d37', 'k': '\u0d38', 'l': '\u0d39', 'm': '\u0d20',
    # vowel signs / marks
    'n': '\u0d3e', 'o': '\u0d3f', 'p': '\u0d41', 'q': '\u0d42',
    'r': '\u0d43', 's': '\u0d46', 't': '\u0d47', 'u': '\u0d48',
    'v': '\u0d4d', 'w': '\u0d02',
    'y': '\u0d40',   #? hook -> ii
    'z': '\u0d4d\u0d30',  #? ┘ -> ra-vattu
    'braceleft': '\u0d4d\u0d32',  #? l-curve -> la-vattu
    # conjuncts
    'degree': '\u0d15\u0d4d\u0d15',
    'cent': '\u0d15\u0d4d\u0d32',
    'sterling': '\u0d15\u0d4d\u0d37',
    'section': '\u0d17\u0d4d\u0d17',
    'bullet': '\u0d17\u0d4d\u0d32',
    'paragraph': '\u0d19\u0d4d\u0d15',
    'germandbls': '\u0d19\u0d4d\u0d17',
    'registered': '\u0d1a\u0d4d\u0d1a',
    'copyright': '\u0d1e\u0d4d\u0d1a',
    'trademark': '\u0d23\u0d4d\u0d1f',  #?
    'acute': '\u0d26\u0d4d\u0d27',  #?
    'dieresis': '\u0d23\u0d4d',
    'notequal': '\u0d23\u0d4d\u0d21',
    'AE': '\u0d23\u0d4d\u0d23',
    'Oslash': '\u0d24\u0d4d\u0d24',
    'plusminus': '\u0d26\u0d4d\u0d26',  #?
    'lessequal': '\u0d27\u0d4d\u0d27',  #?
    'greaterequal': '\u0d28\u0d4d',
    'yen': '\u0d28\u0d4d\u0d24',
    'mu': '\u0d28\u0d4d\u0d26',
    'partialdiff': '\u0d28\u0d4d\u0d28',
    'summation': '\u0d2e\u0d4d\u0d2a',
    'product': '\u0d2a\u0d4d\u0d2a',
    'pi': '\u0d2a\u0d4d\u0d32',  #?
    'ordfeminine': '\u0d2c\u0d4d\u0d2c',
    'ordmasculine': '\u0d2e\u0d4d\u0d2a',
    'Omega': '\u0d2e\u0d4d\u0d2e',
    'oslash': '\u0d2f\u0d4d\u0d2f',
    'questiondown': '\u0d30\u0d4d',
    'logicalnot': '\u0d32\u0d4d',
    'radical': '\u0d32\u0d4d\u0d32',
    'florin': '\u0d36\u0d4d',  #?
    'approxequal': '\u0d33\u0d4d\u0d33',
    'Delta': '\u0d35\u0d4d\u0d35',
    'guillemotright': '\u0d36\u0d4d\u0d36',  #?
    'nbspace': '\u0d38\u0d4d\u0d38',
    'Atilde': '\u0d38\u0d4d\u0d1f',  #?
    'emdash': '\u0d1a\u0d4d\u0d1a',  #? duplicate of registered?
    'quotedblleft': '\u0d39\u0d4d\u0d2e',
    'quoteleft': '\u0d38\u0d4d\u0d25',
    'ydieresis': '\u0d38\u0d4d\u0d2e',
    'fraction': '\u0d1c\u0d4d\u0d1e',  #?
    'guilsinglright': '\u0d36\u0d4d\u0d1a',  #?
    'fl': '\u0d24\u0d4d\u0d2e',
    'daggerdbl': '\u0d15\u0d4d\u0d24',
    'quotesinglbase': '\u0d28\u0d4d\u0d1f',  #?
    'perthousand': '\u0d31\u0d4d\u0d31',  #?
    'ogonek': '\u2013',
}
# end of table
