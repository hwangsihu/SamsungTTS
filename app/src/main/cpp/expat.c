// The subset of expat's API the 2014 engine imports, backed by a small non-validating parser.
#include <stdlib.h>
#include <string.h>

typedef char XML_Char;
typedef void (*XML_StartElementHandler)(void *user, const XML_Char *name, const XML_Char **atts);
typedef void (*XML_EndElementHandler)(void *user, const XML_Char *name);
typedef void (*XML_CharacterDataHandler)(void *user, const XML_Char *s, int len);

typedef struct parser {
    void *user;
    XML_StartElementHandler start;
    XML_EndElementHandler end;
    XML_CharacterDataHandler text;
    char *buf;
    size_t len;
    size_t cap;
} *XML_Parser;

enum { XML_STATUS_ERROR = 0, XML_STATUS_OK = 1 };
enum { MAX_ATTS = 32 };

XML_Parser XML_ParserCreate(const XML_Char *encoding) {
    (void) encoding;
    return calloc(1, sizeof(struct parser));
}

void XML_ParserFree(XML_Parser p) {
    if (p != NULL) {
        free(p->buf);
        free(p);
    }
}

void XML_SetUserData(XML_Parser p, void *user) {
    p->user = user;
}

void XML_SetElementHandler(XML_Parser p, XML_StartElementHandler start, XML_EndElementHandler end) {
    p->start = start;
    p->end = end;
}

void XML_SetCharacterDataHandler(XML_Parser p, XML_CharacterDataHandler text) {
    p->text = text;
}

static size_t put_utf8(char *out, unsigned long cp) {
    if (cp < 0x80) {
        out[0] = (char) cp;
        return 1;
    }
    if (cp < 0x800) {
        out[0] = (char) (0xC0 | (cp >> 6));
        out[1] = (char) (0x80 | (cp & 0x3F));
        return 2;
    }
    if (cp < 0x10000) {
        out[0] = (char) (0xE0 | (cp >> 12));
        out[1] = (char) (0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char) (0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = (char) (0xF0 | (cp >> 18));
    out[1] = (char) (0x80 | ((cp >> 12) & 0x3F));
    out[2] = (char) (0x80 | ((cp >> 6) & 0x3F));
    out[3] = (char) (0x80 | (cp & 0x3F));
    return 4;
}

// Decodes entity references in place and returns the new length.
static size_t unescape(char *s, size_t n) {
    static const struct { const char *name; char c; } named[] = {
        {"lt;", '<'}, {"gt;", '>'}, {"amp;", '&'}, {"quot;", '"'}, {"apos;", '\''},
    };
    size_t r = 0, w = 0;
    while (r < n) {
        if (s[r] != '&') {
            s[w++] = s[r++];
            continue;
        }
        size_t i;
        for (i = 0; i < sizeof(named) / sizeof(named[0]); i++) {
            size_t len = strlen(named[i].name);
            if (r + 1 + len <= n && strncmp(s + r + 1, named[i].name, len) == 0) {
                s[w++] = named[i].c;
                r += 1 + len;
                break;
            }
        }
        if (i < sizeof(named) / sizeof(named[0])) {
            continue;
        }
        if (r + 2 < n && s[r + 1] == '#') {
            char *end;
            int hex = s[r + 2] == 'x' || s[r + 2] == 'X';
            unsigned long cp = strtoul(s + r + (hex ? 3 : 2), &end, hex ? 16 : 10);
            if (end < s + n && *end == ';' && cp > 0) {
                char tmp[4];
                size_t k = put_utf8(tmp, cp);
                memcpy(s + w, tmp, k);
                w += k;
                r = (size_t) (end - s) + 1;
                continue;
            }
        }
        s[w++] = s[r++];
    }
    return w;
}

static int is_space(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

static char *skip_past(char *s, char *end, const char *marker) {
    size_t m = strlen(marker);
    for (; s + m <= end; s++) {
        if (memcmp(s, marker, m) == 0) {
            return s + m;
        }
    }
    return NULL;
}

static int parse(XML_Parser p, char *s, char *end) {
    while (s < end) {
        if (*s != '<') {
            char *lt = memchr(s, '<', (size_t) (end - s));
            char *stop = lt != NULL ? lt : end;
            size_t len = unescape(s, (size_t) (stop - s));
            if (len > 0 && p->text != NULL) {
                p->text(p->user, s, (int) len);
            }
            s = stop;
            continue;
        }
        if (end - s >= 4 && memcmp(s, "<!--", 4) == 0) {
            s = skip_past(s + 4, end, "-->");
        } else if (end - s >= 9 && memcmp(s, "<![CDATA[", 9) == 0) {
            char *body = s + 9;
            s = skip_past(body, end, "]]>");
            if (s != NULL && p->text != NULL && s - 3 > body) {
                p->text(p->user, body, (int) (s - 3 - body));
            }
        } else if (end - s >= 2 && (s[1] == '?' || s[1] == '!')) {
            s = skip_past(s + 2, end, ">");
        } else if (end - s >= 2 && s[1] == '/') {
            char *name = s + 2;
            char *gt = memchr(name, '>', (size_t) (end - name));
            if (gt == NULL) {
                return XML_STATUS_ERROR;
            }
            char *e = gt;
            while (e > name && is_space(e[-1])) {
                e--;
            }
            *e = '\0';
            if (p->end != NULL) {
                p->end(p->user, name);
            }
            s = gt + 1;
        } else {
            char *name = s + 1;
            char *q = name;
            while (q < end && !is_space(*q) && *q != '>' && *q != '/') {
                q++;
            }
            if (q >= end) {
                return XML_STATUS_ERROR;
            }
            char after = *q;
            *q = '\0';
            const XML_Char *atts[MAX_ATTS * 2 + 1];
            int n = 0;
            int self_closing = 0;
            q++;
            if (after == '>') {
                goto done;
            }
            if (after == '/') {
                self_closing = 1;
                q = memchr(q, '>', (size_t) (end - q));
                if (q == NULL) {
                    return XML_STATUS_ERROR;
                }
                q++;
                goto done;
            }
            for (;;) {
                while (q < end && is_space(*q)) {
                    q++;
                }
                if (q >= end) {
                    return XML_STATUS_ERROR;
                }
                if (*q == '>') {
                    q++;
                    break;
                }
                if (*q == '/') {
                    self_closing = 1;
                    q = memchr(q, '>', (size_t) (end - q));
                    if (q == NULL) {
                        return XML_STATUS_ERROR;
                    }
                    q++;
                    break;
                }
                char *an = q;
                while (q < end && *q != '=' && !is_space(*q) && *q != '>') {
                    q++;
                }
                char *an_end = q;
                while (q < end && is_space(*q)) {
                    q++;
                }
                if (q >= end || *q != '=') {
                    return XML_STATUS_ERROR;
                }
                q++;
                while (q < end && is_space(*q)) {
                    q++;
                }
                if (q >= end || (*q != '"' && *q != '\'')) {
                    return XML_STATUS_ERROR;
                }
                char quote = *q++;
                char *av = q;
                char *av_end = memchr(q, quote, (size_t) (end - q));
                if (av_end == NULL) {
                    return XML_STATUS_ERROR;
                }
                q = av_end + 1;
                *an_end = '\0';
                av[unescape(av, (size_t) (av_end - av))] = '\0';
                if (n < MAX_ATTS * 2) {
                    atts[n++] = an;
                    atts[n++] = av;
                }
            }
done:
            atts[n] = NULL;
            if (p->start != NULL) {
                p->start(p->user, name, atts);
            }
            if (self_closing && p->end != NULL) {
                p->end(p->user, name);
            }
            s = q;
            continue;
        }
        if (s == NULL) {
            return XML_STATUS_ERROR;
        }
    }
    return XML_STATUS_OK;
}

int XML_Parse(XML_Parser p, const char *s, int len, int is_final) {
    if (len > 0) {
        if (p->len + (size_t) len + 1 > p->cap) {
            size_t cap = (p->len + (size_t) len + 1) * 2;
            char *grown = realloc(p->buf, cap);
            if (grown == NULL) {
                return XML_STATUS_ERROR;
            }
            p->buf = grown;
            p->cap = cap;
        }
        memcpy(p->buf + p->len, s, (size_t) len);
        p->len += (size_t) len;
    }
    if (!is_final) {
        return XML_STATUS_OK;
    }
    int status = p->buf != NULL ? parse(p, p->buf, p->buf + p->len) : XML_STATUS_OK;
    p->len = 0;
    return status;
}
