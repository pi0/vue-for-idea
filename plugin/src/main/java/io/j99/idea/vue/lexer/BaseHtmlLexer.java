/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.j99.idea.vue.lexer;

import com.intellij.codeInsight.completion.CompletionUtilCore;
import com.intellij.lang.HtmlScriptContentProvider;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageHtmlScriptContentProvider;
import com.intellij.lang.css.CSSLanguage;
import com.intellij.lang.javascript.JavaScriptSupportLoader;
import com.intellij.lang.javascript.JavascriptLanguage;
import com.intellij.lexer.DelegateLexer;
import com.intellij.lexer.EmbeddedTokenTypesProvider;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.text.CharArrayUtil;
import org.coffeescript.CoffeeScriptLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * @author Maxim.Mossienko
 */
abstract class BaseHtmlLexer extends DelegateLexer {
    protected static final int BASE_STATE_MASK = 0x3F;
    private static final int SEEN_STYLE = 0x40;
    private static final int SEEN_TAG = 0x80;
    private static final int SEEN_SCRIPT = 0x100;
    private static final int SEEN_ATTRIBUTE = 0x200;
    private static final int SEEN_CONTENT_TYPE = 0x400;
    private static final int SEEN_STYLESHEET_TYPE = 0x800;
    protected static final int BASE_STATE_SHIFT = 11;
    @Nullable
    protected static final Language ourDefaultLanguage = JavaScriptSupportLoader.ECMA_SCRIPT_6;
    @Nullable
    protected static final Language ourDefaultStyleLanguage = Language.findLanguageByID("CSS");

    protected boolean seenTag;
    protected boolean seenAttribute;
    protected boolean seenStyle;
    protected boolean seenLangAttribute = false;
    protected boolean seenScript;

    @Nullable
    protected String scriptType = null;
    @Nullable
    protected String styleType = null;

    private final boolean caseInsensitive;
    private boolean seenContentType;
    private boolean seenStylesheetType;
    private CharSequence cachedBufferSequence;
    private Lexer lexerOfCacheBufferSequence;

    static final TokenSet TOKENS_TO_MERGE = TokenSet.create(XmlTokenType.XML_COMMENT_CHARACTERS, XmlTokenType.XML_WHITE_SPACE, XmlTokenType.XML_REAL_WHITE_SPACE,
            XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN, XmlTokenType.XML_DATA_CHARACTERS,
            XmlTokenType.XML_TAG_CHARACTERS);

    public interface TokenHandler {
        void handleElement(Lexer lexer);
    }

    public class XmlNameHandler implements TokenHandler {
        @NonNls
        private static final String TOKEN_SCRIPT = "script";
        @NonNls
        private static final String TOKEN_STYLE = "style";

        @Override
        public void handleElement(Lexer lexer) {
            final CharSequence buffer;
            if (lexerOfCacheBufferSequence == lexer) {
                buffer = cachedBufferSequence;
            } else {
                cachedBufferSequence = lexer.getBufferSequence();
                buffer = cachedBufferSequence;
                lexerOfCacheBufferSequence = lexer;
            }
            int tokenStart = lexer.getTokenStart();
            final char firstCh = buffer.charAt(tokenStart);

            @NonNls String attrName = TreeUtil.getTokenText(lexer);
            if (seenScript && !seenTag) {
                seenContentType = false;
                if ((firstCh == 'l') || (caseInsensitive && (firstCh == 'L'))) {
                    seenContentType = Comparing.strEqual("lang", attrName, !caseInsensitive);
                    seenLangAttribute = true;
                }
                return;
            }
            if (seenStyle && !seenTag) {
                seenStylesheetType = false;
                if (firstCh == 'l' || caseInsensitive && firstCh == 'L') {
                    seenStylesheetType = Comparing.strEqual("lang", attrName, !caseInsensitive);
                    seenLangAttribute = true;
                }

                return;
            }

            if (firstCh != 'o' && firstCh != 's' &&
                    (!caseInsensitive || (firstCh != 'S' && firstCh != 'O'))
                    ) {
                return; // optimization
            }

            String name = attrName;
            if (caseInsensitive) name = name.toLowerCase();

            final boolean style = name.equals(TOKEN_STYLE);
            final int state = getState() & BASE_STATE_MASK;
            final boolean script = name.equals(TOKEN_SCRIPT);

            if (style || script) {
                // encountered tag name in end of tag
                if (seenTag) {
                    if (isHtmlTagState(state)) {
                        seenTag = false;
                    }
                    return;
                }

                seenStyle = style;
                seenScript = script;

                if (!isHtmlTagState(state)) {
                    seenAttribute = true;
                }
            }
        }
    }

    class XmlAttributeValueEndHandler implements TokenHandler {
        @Override
        public void handleElement(Lexer lexer) {
            if (seenAttribute) {
                seenStyle = false;
                seenScript = false;
                seenAttribute = false;
            }
            seenContentType = false;
            seenStylesheetType = false;

        }
    }


    class XmlAttributeValueHandler implements TokenHandler {
        @Override
        public void handleElement(Lexer lexer) {
            if (seenContentType && seenScript && !seenAttribute) {
                @NonNls String mimeType = TreeUtil.getTokenText(lexer);
                scriptType = caseInsensitive ? mimeType.toLowerCase(Locale.US) : mimeType;
            }
            if (seenStylesheetType && seenStyle && !seenAttribute) {
                @NonNls String lang = TreeUtil.getTokenText(lexer).trim();
                styleType = caseInsensitive ? lang.toLowerCase(Locale.US) : lang;
            }
        }
    }

    @Nullable
    protected Language getScriptLanguage() {
        Collection<Language> instancesByMimeType = Language.findInstancesByMimeType(scriptType != null ? scriptType.trim() : null);
        Language language = instancesByMimeType.isEmpty() ? null : instancesByMimeType.iterator().next();
        return language;
    }

    @Nullable
    protected Language getStyleLanguage() {
        if (ourDefaultStyleLanguage != null && styleType != null) {
            String[] split = styleType.split("\\?");
            if (split.length > 0) {
                styleType = split[0];
            }
            List<Language> dialects = ourDefaultStyleLanguage.getDialects();
            dialects.add(CSSLanguage.INSTANCE);
            for (Language language : dialects) {
                if (styleType.equals(language.getID().toLowerCase(Locale.US))) {
                    return language;
                }
            }
        }
        return null;

    }

    @Nullable
    protected IElementType getCurrentScriptElementType() {
        HtmlScriptContentProvider scriptContentProvider = findScriptContentProvider(scriptType);
        return scriptContentProvider == null ? null : scriptContentProvider.getScriptElementType();
    }

    @Nullable
    protected IElementType getCurrentStylesheetElementType() {
        Language language = getStyleLanguage();
        if (language != null && seenLangAttribute && !seenAttribute) {
            for (EmbeddedTokenTypesProvider provider : EmbeddedTokenTypesProvider.EXTENSION_POINT_NAME.getExtensions()) {
                IElementType elementType = provider.getElementType();
                if (language.is(elementType.getLanguage())) {
                    return elementType;
                }
            }
        }
        return null;
    }

    @Nullable
    protected static HtmlScriptContentProvider findScriptContentProvider(@Nullable String mimeType) {
        if (StringUtil.isEmpty(mimeType)) {
            return null;
        }
        final Language language;
        if (mimeType.equals("coffee")) {
            language = CoffeeScriptLanguage.INSTANCE;

        } else if (mimeType.equals("JavaScript") || mimeType.equals("javascript")) {
            language = JavascriptLanguage.INSTANCE;

        } else if (mimeType.equals("es6") || mimeType.equals("babel")) {
            language = JavaScriptSupportLoader.ECMA_SCRIPT_6;
        } else {
            language = null;
        }
        if (language != null) {
            HtmlScriptContentProvider scriptContentProvider = LanguageHtmlScriptContentProvider.getScriptContentProvider(language);
            if (scriptContentProvider != null) {
                return scriptContentProvider;
            }
        }
        return null;
    }

    class XmlTagClosedHandler implements TokenHandler {
        @Override
        public void handleElement(Lexer lexer) {
            if (seenAttribute) {
                seenScript = false;
                seenStyle = false;

                seenAttribute = false;
            } else {
                if (seenStyle || seenScript) {
                    seenTag = true;
                }
            }
        }
    }

    class XmlTagEndHandler implements TokenHandler {
        @Override
        public void handleElement(Lexer lexer) {
            seenStyle = false;
            seenScript = false;
            seenAttribute = false;
            seenContentType = false;
            seenStylesheetType = false;
            seenLangAttribute = false;
            scriptType = null;
            styleType = null;

        }
    }

    private final HashMap<IElementType, TokenHandler> tokenHandlers = new HashMap<IElementType, TokenHandler>();

    protected BaseHtmlLexer(Lexer _baseLexer, boolean _caseInsensitive) {
        super(_baseLexer);
        caseInsensitive = _caseInsensitive;

        XmlNameHandler value = new XmlNameHandler();
        tokenHandlers.put(XmlTokenType.XML_NAME, value);
        tokenHandlers.put(XmlTokenType.XML_TAG_NAME, value);
        tokenHandlers.put(XmlTokenType.XML_TAG_END, new XmlTagClosedHandler());
        tokenHandlers.put(XmlTokenType.XML_END_TAG_START, new XmlTagEndHandler());
        tokenHandlers.put(XmlTokenType.XML_EMPTY_ELEMENT_END, new XmlTagEndHandler());
        tokenHandlers.put(XmlTokenType.XML_ATTRIBUTE_VALUE_END_DELIMITER, new XmlAttributeValueEndHandler());
        tokenHandlers.put(XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN, new XmlAttributeValueHandler());
    }

    protected void registerHandler(IElementType elementType, TokenHandler value) {
        final TokenHandler tokenHandler = tokenHandlers.get(elementType);

        if (tokenHandler != null) {
            final TokenHandler newHandler = value;
            value = new TokenHandler() {
                @Override
                public void handleElement(final Lexer lexer) {
                    tokenHandler.handleElement(lexer);
                    newHandler.handleElement(lexer);
                }
            };
        }

        tokenHandlers.put(elementType, value);
    }

    @Override
    public void start(@NotNull final CharSequence buffer, final int startOffset, final int endOffset, final int initialState) {
        initState(initialState);
        super.start(buffer, startOffset, endOffset, initialState & BASE_STATE_MASK);
    }

    private void initState(final int initialState) {
        seenScript = (initialState & SEEN_SCRIPT) != 0;
        seenStyle = (initialState & SEEN_STYLE) != 0;
        seenTag = (initialState & SEEN_TAG) != 0;
        seenAttribute = (initialState & SEEN_ATTRIBUTE) != 0;
        seenContentType = (initialState & SEEN_CONTENT_TYPE) != 0;
        seenStylesheetType = (initialState & SEEN_STYLESHEET_TYPE) != 0;
        lexerOfCacheBufferSequence = null;
        cachedBufferSequence = null;
    }

    protected int skipToTheEndOfTheEmbeddment() {
        Lexer base = getDelegate();
        int tokenEnd = base.getTokenEnd();
        int lastState = 0;
        int lastStart = 0;

        final CharSequence buf = base.getBufferSequence();
        final char[] bufArray = CharArrayUtil.fromSequenceWithoutCopying(buf);

        if (seenTag) {
            FoundEnd:
            while (true) {
                FoundEndOfTag:
                while (base.getTokenType() != XmlTokenType.XML_END_TAG_START) {
                    if (base.getTokenType() == XmlTokenType.XML_COMMENT_CHARACTERS) {
                        // we should terminate on first occurence of </
                        final int end = base.getTokenEnd();

                        for (int i = base.getTokenStart(); i < end; ++i) {
                            if ((bufArray != null ? bufArray[i] : buf.charAt(i)) == '<' &&
                                    i + 1 < end &&
                                    (bufArray != null ? bufArray[i + 1] : buf.charAt(i + 1)) == '/') {
                                tokenEnd = i;
                                lastStart = i - 1;
                                lastState = 0;

                                break FoundEndOfTag;
                            }
                        }
                    }

                    lastState = base.getState();
                    tokenEnd = base.getTokenEnd();
                    lastStart = base.getTokenStart();
                    if (tokenEnd == getBufferEnd()) break FoundEnd;
                    base.advance();
                }

                // check if next is script
                if (base.getTokenType() != XmlTokenType.XML_END_TAG_START) { // we are inside comment
                    base.start(buf, lastStart + 1, getBufferEnd(), lastState);
                    base.getTokenType();
                    base.advance();
                } else {
                    base.advance();
                }

                while (XmlTokenType.WHITESPACES.contains(base.getTokenType())) {
                    base.advance();
                }

                if (base.getTokenType() == XmlTokenType.XML_NAME) {
                    String name = TreeUtil.getTokenText(base);
                    if (caseInsensitive) name = name.toLowerCase();

                    if ((hasSeenScript() && XmlNameHandler.TOKEN_SCRIPT.equals(name)) ||
                            (hasSeenStyle() && XmlNameHandler.TOKEN_STYLE.equals(name)) ||
                            CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED.equalsIgnoreCase(name)) {
                        break; // really found end
                    }
                }
            }

            base.start(buf, lastStart, getBufferEnd(), lastState);
            base.getTokenType();
        } else if (seenAttribute) {
            while (true) {
                if (!isValidAttributeValueTokenType(base.getTokenType())) break;

                tokenEnd = base.getTokenEnd();
                lastState = base.getState();
                lastStart = base.getTokenStart();

                if (tokenEnd == getBufferEnd()) break;
                base.advance();
            }

            base.start(buf, lastStart, getBufferEnd(), lastState);
            base.getTokenType();
        }
        return tokenEnd;
    }

    protected boolean isValidAttributeValueTokenType(final IElementType tokenType) {
        return tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN ||
                tokenType == XmlTokenType.XML_ENTITY_REF_TOKEN ||
                tokenType == XmlTokenType.XML_CHAR_ENTITY_REF;
    }

    @Override
    public void advance() {
        super.advance();
        IElementType type = getDelegate().getTokenType();
        TokenHandler tokenHandler = tokenHandlers.get(type);
        if (tokenHandler != null) tokenHandler.handleElement(this);
    }


    @Override
    public int getState() {
        int state = super.getState();

        state |= ((seenScript) ? SEEN_SCRIPT : 0);
        state |= ((seenTag) ? SEEN_TAG : 0);
        state |= ((seenStyle) ? SEEN_STYLE : 0);
        state |= ((seenAttribute) ? SEEN_ATTRIBUTE : 0);
        state |= ((seenContentType) ? SEEN_CONTENT_TYPE : 0);
        state |= ((seenStylesheetType) ? SEEN_STYLESHEET_TYPE : 0);

        return state;
    }

    protected final boolean hasSeenStyle() {
        return seenStyle;
    }

    protected final boolean hasSeenAttribute() {
        return seenAttribute;
    }

    protected final boolean hasSeenTag() {
        return seenTag;
    }

    protected boolean hasSeenScript() {
        return seenScript;
    }

    protected abstract boolean isHtmlTagState(int state);
}
