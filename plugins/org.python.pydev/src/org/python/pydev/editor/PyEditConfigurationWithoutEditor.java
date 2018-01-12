/**
 * Copyright (c) 2005-2013 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Eclipse Public License (EPL).
 * Please see the license.txt included with this distribution for details.
 * Any modifications to this file must keep this entire header intact.
 */
package org.python.pydev.editor;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextDoubleClickStrategy;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.MonoReconciler;
import org.eclipse.jface.text.rules.DefaultDamagerRepairer;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration;
import org.eclipse.ui.texteditor.spelling.SpellingService;
import org.python.pydev.core.IGrammarVersionProvider;
import org.python.pydev.core.IIndentPrefs;
import org.python.pydev.core.IPyEditConfigurationWithoutEditor;
import org.python.pydev.core.IPythonPartitions;
import org.python.pydev.core.log.Log;
import org.python.pydev.editor.autoedit.PyAutoIndentStrategy;
import org.python.pydev.editor.codecompletion.PyContentAssistant;
import org.python.pydev.editor.preferences.PydevEditorPrefs;
import org.python.pydev.plugin.PydevPlugin;
import org.python.pydev.shared_core.string.FastStringBuffer;
import org.python.pydev.ui.ColorAndStyleCache;

public class PyEditConfigurationWithoutEditor extends TextSourceViewerConfiguration
        implements IPyEditConfigurationWithoutEditor {

    private ColorAndStyleCache colorCache;

    private PyAutoIndentStrategy autoIndentStrategy;

    private String[] indentPrefixes = { "    ", "\t", "" };

    private PyPresentationReconciler reconciler;

    private PyCodeScanner codeScanner;

    private PyColoredScanner commentScanner, backquotesScanner;

    private PyStringScanner stringScanner;

    private PyFStringScanner fStringScanner;

    private PyUnicodeScanner unicodeScanner;

    private PyBytesOrUnicodeScanner bytesOrUnicodeScanner;

    public PyContentAssistant pyContentAssistant = new PyContentAssistant();

    private final Object lock = new Object();

    private IGrammarVersionProvider grammarVersionProvider;

    public PyEditConfigurationWithoutEditor(ColorAndStyleCache colorManager, IPreferenceStore preferenceStore,
            IGrammarVersionProvider grammarVersionProvider) {
        super(preferenceStore);
        colorCache = colorManager;
        this.grammarVersionProvider = grammarVersionProvider;
    }

    /**
     * Has to return all the types generated by partition scanner.
     *
     * The SourceViewer will ignore double-clicks and any other configuration behaviors inside any partition not declared here
     */
    @Override
    public String[] getConfiguredContentTypes(ISourceViewer sourceViewer) {
        return new String[] {
                IDocument.DEFAULT_CONTENT_TYPE,
                IPythonPartitions.PY_COMMENT,
                IPythonPartitions.PY_BACKQUOTES,

                IPythonPartitions.PY_SINGLELINE_FSTRING1,
                IPythonPartitions.PY_SINGLELINE_FSTRING2,
                IPythonPartitions.PY_MULTILINE_FSTRING1,
                IPythonPartitions.PY_MULTILINE_FSTRING2,

                IPythonPartitions.PY_SINGLELINE_BYTES1,
                IPythonPartitions.PY_SINGLELINE_BYTES2,
                IPythonPartitions.PY_MULTILINE_BYTES1,
                IPythonPartitions.PY_MULTILINE_BYTES2,

                IPythonPartitions.PY_SINGLELINE_UNICODE1,
                IPythonPartitions.PY_SINGLELINE_UNICODE2,
                IPythonPartitions.PY_MULTILINE_UNICODE1,
                IPythonPartitions.PY_MULTILINE_UNICODE2,

                IPythonPartitions.PY_SINGLELINE_BYTES_OR_UNICODE1,
                IPythonPartitions.PY_SINGLELINE_BYTES_OR_UNICODE2,
                IPythonPartitions.PY_MULTILINE_BYTES_OR_UNICODE1,
                IPythonPartitions.PY_MULTILINE_BYTES_OR_UNICODE2
        };
    }

    @Override
    public String getConfiguredDocumentPartitioning(ISourceViewer sourceViewer) {
        return IPythonPartitions.PYTHON_PARTITION_TYPE;
    }

    /**
     * Cache the result, because we'll get asked for it multiple times Now, we always return the PyAutoIndentStrategy. (even on commented lines).
     *
     * @return PyAutoIndentStrategy which deals with spaces/tabs
     */
    @Override
    public IAutoEditStrategy[] getAutoEditStrategies(ISourceViewer sourceViewer, String contentType) {
        return new IAutoEditStrategy[] { getPyAutoIndentStrategy(null) };
    }

    @Override
    public IReconciler getReconciler(ISourceViewer sourceViewer) {
        if (fPreferenceStore == null || !fPreferenceStore.getBoolean(SpellingService.PREFERENCE_SPELLING_ENABLED)) {
            return null;
        }

        SpellingService spellingService = EditorsUI.getSpellingService();
        if (spellingService.getActiveSpellingEngineDescriptor(fPreferenceStore) == null) {
            return null;
        }

        //Overridden (just) to return a PyReconciler!
        IReconcilingStrategy strategy = new PyReconciler(sourceViewer, spellingService);

        MonoReconciler reconciler = new MonoReconciler(strategy, false);
        reconciler.setIsIncrementalReconciler(false);
        reconciler.setProgressMonitor(new NullProgressMonitor());
        reconciler.setDelay(500);
        return reconciler;
    }

    /**
     * Cache the result, because we'll get asked for it multiple times Now, we always return the PyAutoIndentStrategy. (even on commented lines).
     * @param projectAdaptable
     *
     * @return PyAutoIndentStrategy which deals with spaces/tabs
     */
    public PyAutoIndentStrategy getPyAutoIndentStrategy(IAdaptable projectAdaptable) {
        if (autoIndentStrategy == null) {
            if (projectAdaptable == null) {
                Log.log("Received null for projectAdaptable. Usig default preferences instead of project-specific preferences.");
            }
            autoIndentStrategy = new PyAutoIndentStrategy(projectAdaptable);
        }
        return autoIndentStrategy;
    }

    /**
     * Recalculates indent prefixes based upon preferences
     *
     * we hold onto the same array SourceViewer has, and write directly into it. This is because there is no way to tell SourceViewer that indent prefixes have changed. And we need this functionality
     * when user resets the tabs vs. spaces preference
     */
    public void resetIndentPrefixes() {
        IIndentPrefs indentPrefs = (getPyAutoIndentStrategy(null)).getIndentPrefs();
        int tabWidth = indentPrefs.getTabWidth();
        FastStringBuffer spaces = new FastStringBuffer(8);
        spaces.appendN(' ', tabWidth);

        boolean spacesFirst = indentPrefs.getUseSpaces(true);

        if (spacesFirst) {
            indentPrefixes[0] = spaces.toString();
            indentPrefixes[1] = "\t";
        } else {
            indentPrefixes[0] = "\t";
            indentPrefixes[1] = spaces.toString();
        }
    }

    /**
     * Prefixes used in shift-left/shift-right editor operations
     *
     * shift-right uses prefix[0] shift-left removes a single instance of the first prefix from the array that matches
     *
     * @see org.eclipse.jface.text.source.SourceViewerConfiguration#getIndentPrefixes(org.eclipse.jface.text.source.ISourceViewer, java.lang.String)
     */
    @Override
    public String[] getIndentPrefixes(ISourceViewer sourceViewer, String contentType) {
        resetIndentPrefixes();
        sourceViewer.setIndentPrefixes(indentPrefixes, contentType);
        return indentPrefixes;
    }

    /**
     * Just the default double-click strategy for now. But we should be smarter.
     *
     * @see org.eclipse.jface.text.source.SourceViewerConfiguration#getDoubleClickStrategy(org.eclipse.jface.text.source.ISourceViewer, java.lang.String)
     */
    @Override
    public ITextDoubleClickStrategy getDoubleClickStrategy(ISourceViewer sourceViewer, String contentType) {
        return new PyDoubleClickStrategy(contentType);
    }

    /**
     * TabWidth is defined inside pydev preferences.
     *
     * Python uses its own tab width, since I think that its standard is 8
     */
    @Override
    public int getTabWidth(ISourceViewer sourceViewer) {
        return getPyAutoIndentStrategy(null).getIndentPrefs().getTabWidth();
    }

    @Override
    public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer) {

        synchronized (lock) {
            if (reconciler == null) {
                reconciler = new PyPresentationReconciler();
                reconciler.setDocumentPartitioning(IPythonPartitions.PYTHON_PARTITION_TYPE);

                DefaultDamagerRepairer dr;

                // DefaultDamagerRepairer implements both IPresentationDamager, IPresentationRepairer
                // IPresentationDamager::getDamageRegion does not scan, just
                // returns the intersection of document event, and partition region
                // IPresentationRepairer::createPresentation scans
                // gets each token, and sets text attributes according to token

                // We need to cover all the content types from PyPartitionScanner

                // Comments have uniform color
                commentScanner = new PyColoredScanner(colorCache, PydevEditorPrefs.COMMENT_COLOR);
                dr = new DefaultDamagerRepairer(commentScanner);
                reconciler.setDamager(dr, IPythonPartitions.PY_COMMENT);
                reconciler.setRepairer(dr, IPythonPartitions.PY_COMMENT);

                // Backquotes have uniform color
                backquotesScanner = new PyColoredScanner(colorCache, PydevEditorPrefs.BACKQUOTES_COLOR);
                dr = new DefaultDamagerRepairer(backquotesScanner);
                reconciler.setDamager(dr, IPythonPartitions.PY_BACKQUOTES);
                reconciler.setRepairer(dr, IPythonPartitions.PY_BACKQUOTES);

                // Strings have uniform color
                stringScanner = new PyStringScanner(colorCache);
                dr = new DefaultDamagerRepairer(stringScanner);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_BYTES1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_BYTES1);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_BYTES2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_BYTES2);

                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_BYTES1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_BYTES1);
                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_BYTES2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_BYTES2);

                fStringScanner = new PyFStringScanner(colorCache);
                // We have to damage the whole partition because internal tokens may span
                // multiple lines (i.e.: an expression inside an f-string may have
                // multiple lines).
                dr = new FullPartitionDamagerRepairer(fStringScanner);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_FSTRING1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_FSTRING1);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_FSTRING2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_FSTRING2);

                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_FSTRING1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_FSTRING1);
                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_FSTRING2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_FSTRING2);

                unicodeScanner = new PyUnicodeScanner(colorCache);
                dr = new DefaultDamagerRepairer(unicodeScanner);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_UNICODE1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_UNICODE1);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_UNICODE2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_UNICODE2);

                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_UNICODE1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_UNICODE1);
                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_UNICODE2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_UNICODE2);

                bytesOrUnicodeScanner = new PyBytesOrUnicodeScanner(colorCache, grammarVersionProvider, reconciler);
                dr = new DefaultDamagerRepairer(bytesOrUnicodeScanner);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_BYTES_OR_UNICODE1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_BYTES_OR_UNICODE1);
                reconciler.setDamager(dr, IPythonPartitions.PY_SINGLELINE_BYTES_OR_UNICODE2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_SINGLELINE_BYTES_OR_UNICODE2);

                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_BYTES_OR_UNICODE1);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_BYTES_OR_UNICODE1);
                reconciler.setDamager(dr, IPythonPartitions.PY_MULTILINE_BYTES_OR_UNICODE2);
                reconciler.setRepairer(dr, IPythonPartitions.PY_MULTILINE_BYTES_OR_UNICODE2);

                // Default content is code, we need syntax highlighting
                ICodeScannerKeywords codeScannerKeywords = null;
                if (sourceViewer instanceof IAdaptable) {
                    IAdaptable iAdaptable = (IAdaptable) sourceViewer;
                    codeScannerKeywords = iAdaptable.getAdapter(ICodeScannerKeywords.class);
                    codeScanner = new PyCodeScanner(colorCache, codeScannerKeywords);
                } else {
                    codeScanner = new PyCodeScanner(colorCache);
                }
                dr = new DefaultDamagerRepairer(codeScanner);
                reconciler.setDamager(dr, IDocument.DEFAULT_CONTENT_TYPE);
                reconciler.setRepairer(dr, IDocument.DEFAULT_CONTENT_TYPE);
            }
        }

        return reconciler;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.eclipse.jface.text.source.SourceViewerConfiguration#getInformationControlCreator(org.eclipse.jface.text.source.ISourceViewer)
     */
    @Override
    public IInformationControlCreator getInformationControlCreator(ISourceViewer sourceViewer) {
        return PyContentAssistant.createInformationControlCreator(sourceViewer);
    }

    /**
     * Returns the settings for the given section.
     *
     * @param sectionName the section name
     * @return the settings
     * @since pydev 1.3.5
     */
    protected IDialogSettings getSettings(String sectionName) {
        IDialogSettings settings = PydevPlugin.getDefault().getDialogSettings().getSection(sectionName);
        if (settings == null) {
            settings = PydevPlugin.getDefault().getDialogSettings().addNewSection(sectionName);
        }

        return settings;
    }

    //updates the syntax highlighting for the specified preference
    //assumes that that editor colorCache has been updated with the
    //new named color
    public void updateSyntaxColorAndStyle() {
        synchronized (lock) {

            if (reconciler != null) {
                //always update all (too much work in keeping this synchronized by type)
                if (codeScanner != null) {
                    codeScanner.updateColors();
                }

                if (commentScanner != null) {
                    commentScanner.updateColorAndStyle();
                }

                if (stringScanner != null) {
                    stringScanner.updateColorAndStyle();
                }

                if (unicodeScanner != null) {
                    unicodeScanner.updateColorAndStyle();
                }

                if (bytesOrUnicodeScanner != null) {
                    bytesOrUnicodeScanner.updateColorAndStyle();
                }

                if (fStringScanner != null) {
                    fStringScanner.updateColorAndStyle();
                }

                if (backquotesScanner != null) {
                    backquotesScanner.updateColorAndStyle();
                }
            }
        }
    }

}
