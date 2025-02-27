/*******************************************************************************
 * Copyright (c) 2020, 2023 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package io.openliberty.tools.intellij.lsp4mp.lsp4ij.operations.hover;

import com.intellij.lang.documentation.DocumentationProviderEx;
import com.intellij.lang.documentation.ExternalDocumentationHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import io.openliberty.tools.intellij.lsp4mp.lsp4ij.LSPIJUtils;
import io.openliberty.tools.intellij.lsp4mp.lsp4ij.LanguageServiceAccessor;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class LSPTextHover extends DocumentationProviderEx implements ExternalDocumentationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LSPTextHover.class);

    private static final String HEAD = "<head>"; //$NON-NLS-1$

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

    private PsiElement lastElement;
    private int        lastOffset = -1;
    private CompletableFuture<List<Hover>> request,lspRequest;

    public LSPTextHover() {
        LOGGER.info("LSPTextHover");
    }

    public static String styleHtml(Editor editor, String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        Color background = editor.getColorsScheme().getDefaultBackground();
        Color foreground = editor.getColorsScheme().getDefaultForeground();
        // put CSS styling to match Eclipse style
        String style = "<html><head><style TYPE='text/css'>html { " + //$NON-NLS-1$
                (background != null ? "background-color: " + toHTMLrgb(background) + "; " : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                (foreground != null ? "color: " + toHTMLrgb(foreground) + "; " : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                " }</style></head><body>"; //$NON-NLS-1$

        /*int headIndex = html.indexOf(HEAD);
        StringBuilder builder = new StringBuilder(html.length() + style.length());
        builder.append(html.substring(0, headIndex + HEAD.length()));
        builder.append(style);
        builder.append(html.substring(headIndex + HEAD.length()));
        return builder.toString();*/
        StringBuilder builder = new StringBuilder(style);
        builder.append(html).append("</body></html>");
        return builder.toString();
    }

    private static String toHTMLrgb(Color rgb) {
        StringBuilder builder = new StringBuilder(7);
        builder.append('#');
        appendAsHexString(builder, rgb.getRed());
        appendAsHexString(builder, rgb.getGreen());
        appendAsHexString(builder, rgb.getBlue());
        return builder.toString();
    }

    private static void appendAsHexString(StringBuilder buffer, int intValue) {
        String hexValue= Integer.toHexString(intValue);
        if (hexValue.length() == 1) {
            buffer.append('0');
        }
        buffer.append(hexValue);
    }

    @Nullable
    @Override
    public String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        return generateDoc(element, originalElement);
    }

    @Nullable
    @Override
    public List<String> getUrlFor(PsiElement element, PsiElement originalElement) {
        return null;
    }

    private CompletableFuture<Integer> getCursorOffset(Editor editor) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            int offset = -1;
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info != null/* && EditorUtil.isPointOverText(editor, info.getLocation())*/) {
                Point location = info.getLocation();
                SwingUtilities.convertPointFromScreen(location, editor.getContentComponent());
                LogicalPosition position = editor.xyToLogicalPosition(location);
                offset = editor.logicalPositionToOffset(position);
            }
            future.complete(offset);
        });
        return future;
    }

    @Nullable
    @Override
    public String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        PsiElement elem = null;
        Editor editor = null;
        if (element != null) {
            elem = element;
            editor = LSPIJUtils.editorForElement(elem);
        }
        if (editor == null && originalElement != null) {
            // for some files (e.g. xml) element cannot resolve the associated VirtualFile and Editor, so we try to resolve again with originalElement
            editor = LSPIJUtils.editorForElement(originalElement);
            elem = originalElement;
            LOGGER.info("Cannot resolve VirtualFile and Editor for element: " + element.getText() + ". Using originalElement: " + originalElement.getText() + " for hover request.");
        }
        if (editor != null) {
            initiateHoverRequest(elem, editor);
            try {
                String result = request.get(5, TimeUnit.SECONDS).stream()
                        .filter(Objects::nonNull)
                        .map(LSPTextHover::getHoverString)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n\n")) //$NON-NLS-1$
                        .trim();
                if (!result.isEmpty()) {
                    return styleHtml(editor, RENDERER.render(PARSER.parse(result)));
                }
            } catch (ExecutionException | TimeoutException e) {
                String fileName = elem.getContainingFile().getVirtualFile() != null ? String.valueOf(elem.getContainingFile().getVirtualFile()) : String.valueOf(elem.getContainingFile());
                LOGGER.warn(String.format("Unable to generate documentation for %s. ", fileName) + e.getLocalizedMessage(), e);
            } catch (InterruptedException e) {
                LOGGER.warn(e.getLocalizedMessage(), e);
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    protected static @Nullable String getHoverString(Hover hover) {
        Either<List<Either<String, MarkedString>>, MarkupContent> hoverContent = hover.getContents();
        if (hoverContent.isLeft()) {
            List<Either<String, MarkedString>> contents = hoverContent.getLeft();
            if (contents == null || contents.isEmpty()) {
                return null;
            }
            return contents.stream().map(content -> {
                if (content.isLeft()) {
                    return content.getLeft();
                } else if (content.isRight()) {
                    MarkedString markedString = content.getRight();
                    // TODO this won't work fully until markup parser will support syntax
                    // highlighting but will help display
                    // strings with language tags, e.g. without it things after <?php tag aren't
                    // displayed
                    if (markedString.getLanguage() != null && !markedString.getLanguage().isEmpty()) {
                        return String.format("```%s%n%s%n```", markedString.getLanguage(), markedString.getValue()); //$NON-NLS-1$
                    } else {
                        return markedString.getValue();
                    }
                } else {
                    return ""; //$NON-NLS-1$
                }
            }).filter(((Predicate<String>) String::isEmpty).negate()).collect(Collectors.joining("\n\n")); //$NON-NLS-1$ )
        } else {
            return hoverContent.getRight().getValue();
        }
    }


    /**
     * Initialize hover requests with hover (if available) and codelens (if
     * available).
     *
     * @param element
     *            the PSI element.
     * @param editor
     *            the editor.
     */
    private void initiateHoverRequest(PsiElement element, Editor editor) {
        PsiDocumentManager manager = PsiDocumentManager.getInstance(element.getProject());
        final Document document = manager.getDocument(element.getContainingFile());
        this.request = getCursorOffset(editor).thenComposeAsync(offset -> {
            if (offset != -1 && (this.lspRequest == null || !element.equals(this.lastElement) || offset != this.lastOffset)) {
                ApplicationManager.getApplication().runReadAction(() -> { // make sure no writes to the project are underway
                    this.lastElement = element;
                    this.lastOffset = offset;
                    this.lspRequest = LanguageServiceAccessor.getInstance(element.getProject())
                            .getLanguageServers(document, capabilities -> isHoverCapable(capabilities))
                            .thenApplyAsync(languageServers -> // Async is very important here, otherwise the LS Client thread is in
                                    // deadlock and doesn't read bytes from LS
                            {
                                return languageServers.stream()
                                        .map(languageServer -> {
                                            try {
                                                return languageServer.getTextDocumentService()
                                                        .hover(LSPIJUtils.toHoverParams(offset, document)).get();
                                            } catch (ExecutionException e) {
                                                LOGGER.warn(e.getLocalizedMessage(), e);
                                                return null;
                                            } catch (InterruptedException e) {
                                                LOGGER.warn(e.getLocalizedMessage(), e);
                                                Thread.currentThread().interrupt();
                                                return null;
                                            }
                                        }).filter(Objects::nonNull).collect(Collectors.toList());
                            });
                });
            }
            return this.lspRequest;
        });
    }

    private boolean isHoverCapable(ServerCapabilities capabilities) {
        return (capabilities.getHoverProvider().isLeft() && capabilities.getHoverProvider().getLeft()) || capabilities.getHoverProvider().isRight();
    }

    @Nullable
    @Override
    public PsiElement getDocumentationElementForLookupItem(PsiManager psiManager, Object object, PsiElement element) {
        return null;
    }

    @Nullable
    @Override
    public PsiElement getDocumentationElementForLink(PsiManager psiManager, String link, PsiElement context) {
        return null;
    }

    @Nullable
    @Override
    public PsiElement getCustomDocumentationElement(@NotNull Editor editor, @NotNull PsiFile file, @Nullable PsiElement contextElement) {
        return null;
    }

    @Override
    public boolean handleExternal(PsiElement element, PsiElement originalElement) {
        return false;
    }

    @Override
    public boolean handleExternalLink(PsiManager psiManager, String link, PsiElement context) {
        VirtualFile file = getFile(link);
        if (file != null) {
            // use invokeLater to avoid write-unsafe context error
            ApplicationManager.getApplication().invokeLater(()->{
                FileEditorManager.getInstance(psiManager.getProject()).openFile(file, true, true);
                }, psiManager.getProject().getDisposed());
            return true;
        }
        return false;
    }

    @Override
    public boolean canFetchDocumentationLink(String link) {
        return false;
    }

    private VirtualFile getFile(String link) {
        try {
            return VfsUtil.findFileByURL(new URL(link));
        } catch (MalformedURLException e) {
            return null;
        }
    }

    @Override
    public @NotNull String fetchExternalDocumentation(@NotNull String link, @Nullable PsiElement element) {
        return null;
    }
}
