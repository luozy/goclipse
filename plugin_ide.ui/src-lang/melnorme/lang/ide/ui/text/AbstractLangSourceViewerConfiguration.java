/*******************************************************************************
 * Copyright (c) 2015, 2015 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package melnorme.lang.ide.ui.text;

import static melnorme.utilbox.core.Assert.AssertNamespace.assertUnreachable;
import static melnorme.utilbox.core.CoreUtil.array;
import static melnorme.utilbox.core.CoreUtil.tryCast;

import java.util.Map;

import melnorme.lang.ide.ui.CodeFormatterConstants;
import melnorme.lang.ide.ui.CodeFormatterConstants.IndentMode;
import melnorme.lang.ide.ui.EditorSettings_Actual;
import melnorme.lang.ide.ui.LangUIPlugin;
import melnorme.lang.ide.ui.LangUIPlugin_Actual;
import melnorme.lang.ide.ui.editor.AbstractLangEditor;
import melnorme.lang.ide.ui.editor.BestMatchHover;
import melnorme.lang.ide.ui.editor.LangSourceViewer;
import melnorme.lang.ide.ui.editor.structure.LangOutlineInformationControl.OutlineInformationControlCreator;
import melnorme.lang.ide.ui.editor.structure.StructureElementInformationProvider;
import melnorme.lang.ide.ui.editor.text.LangReconciler;
import melnorme.lang.ide.ui.text.completion.CompletionProposalsGrouping;
import melnorme.lang.ide.ui.text.completion.ContentAssistPreferenceHandler;
import melnorme.lang.ide.ui.text.completion.ContentAssistantExt;
import melnorme.lang.ide.ui.text.completion.LangContentAssistProcessor;
import melnorme.lang.ide.ui.text.completion.LangContentAssistProcessor.ContentAssistCategoriesBuilder;
import melnorme.lang.ide.ui.text.util.AutoEditUtils;
import melnorme.utilbox.collections.Indexable;

import org.eclipse.cdt.ui.text.IColorManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.information.IInformationPresenter;
import org.eclipse.jface.text.information.IInformationProvider;
import org.eclipse.jface.text.information.InformationPresenter;
import org.eclipse.jface.text.reconciler.AbstractReconciler;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationHover;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditor;
import org.eclipse.ui.texteditor.ITextEditor;

import _org.eclipse.jdt.internal.ui.text.CompositeReconcilingStrategy;
import _org.eclipse.jdt.internal.ui.text.HTMLAnnotationHover;

public abstract class AbstractLangSourceViewerConfiguration extends SimpleLangSourceViewerConfiguration {
	
	protected final AbstractDecoratedTextEditor editor;
	
	public AbstractLangSourceViewerConfiguration(IPreferenceStore preferenceStore, IColorManager colorManager,
			AbstractDecoratedTextEditor editor) {
		super(preferenceStore, colorManager);
		this.editor = editor;
	}
	
	public AbstractDecoratedTextEditor getEditor() {
		return editor;
	}
	
	public AbstractLangEditor getEditor_asLang() {
		return tryCast(editor, AbstractLangEditor.class);
	}
	
	/* ----------------- Hovers ----------------- */
	
	@Override
	public final ITextHover getTextHover(ISourceViewer sourceViewer, String contentType) {
		return getTextHover(sourceViewer, contentType, ITextViewerExtension2.DEFAULT_HOVER_STATE_MASK);
	}
	
	@Override
	public ITextHover getTextHover(ISourceViewer sourceViewer, String contentType, int stateMask) {
		if(contentType.equals(IDocument.DEFAULT_CONTENT_TYPE)) {
			return new BestMatchHover(getEditor(), stateMask);
		}
		return null;
	}
	
	@Override
	public IAnnotationHover getAnnotationHover(ISourceViewer sourceViewer) {
		return new HTMLAnnotationHover(false) {
			@Override
			protected boolean isIncluded(Annotation annotation) {
				return isShowInVerticalRuler(annotation);
			}
		};
	}
	
	@Override
	public IAnnotationHover getOverviewRulerAnnotationHover(ISourceViewer sourceViewer) {
		return new HTMLAnnotationHover(true) {
			@Override
			protected boolean isIncluded(Annotation annotation) {
				return isShowInOverviewRuler(annotation);
			}
		};
	}
	
	@Override
	public IInformationPresenter getInformationPresenter(ISourceViewer sourceViewer) {
		InformationPresenter presenter = new InformationPresenter(getInformationPresenterControlCreator(sourceViewer));
		presenter.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
		
		// Register information providers
		for (String contentType : getConfiguredContentTypes(sourceViewer)) {
			presenter.setInformationProvider(getInformationProvider(contentType), contentType);
		}
		
		presenter.setSizeConstraints(100, 12, false, true);
		return presenter;
	}
	
	protected abstract IInformationProvider getInformationProvider(String contentType);
	
	protected IInformationControlCreator getInformationPresenterControlCreator(
			@SuppressWarnings("unused") ISourceViewer sourceViewer) {
		return new IInformationControlCreator() {
			@Override
			public IInformationControl createInformationControl(Shell parent) {
				return new DefaultInformationControl(parent, true);
			}
		};
	}
	
	/* ----------------- Navigation operations ----------------- */
	
	@Override 
	protected Map<String, ITextEditor> getHyperlinkDetectorTargets(ISourceViewer sourceViewer) {
		Map<String, ITextEditor> targets = super.getHyperlinkDetectorTargets(sourceViewer);
		targets.put(EditorSettings_Actual.EDITOR_CODE_TARGET, editor); 
		return targets;
	}
	
	public void installOutlinePresenter(final LangSourceViewer sourceViewer) {
		final InformationPresenter presenter = 
				new InformationPresenter(getOutlinePresenterControlCreator(sourceViewer));
		
		presenter.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
		presenter.setAnchor(AbstractInformationControlManager.ANCHOR_GLOBAL);
		
		IInformationProvider provider = new StructureElementInformationProvider(getEditor()); 
		
		for(String contentType : getConfiguredContentTypes(sourceViewer)) {
			presenter.setInformationProvider(provider, contentType);
		}
		
		presenter.setSizeConstraints(50, 20, true, false);
		
		presenter.install(sourceViewer);
		sourceViewer.setOutlinePresenter(presenter);
	}
	
	protected IInformationControlCreator getOutlinePresenterControlCreator(
			@SuppressWarnings("unused") ISourceViewer sourceViewer) {
		return new OutlineInformationControlCreator(this);
	}
	
	/* ----------------- Modification operations ----------------- */
	
	@Override
	public String[] getDefaultPrefixes(ISourceViewer sourceViewer, String contentType) {
		return new String[] { getToggleCommentPrefix(), "" };
	}
	
	protected abstract String getToggleCommentPrefix();
	
	@Override
	public IAutoEditStrategy[] getAutoEditStrategies(ISourceViewer sourceViewer, String contentType) {
		if(IDocument.DEFAULT_CONTENT_TYPE.equals(contentType)) {
			return array(LangUIPlugin_Actual.createAutoEditStrategy(sourceViewer, contentType));
		} else {
			return super.getAutoEditStrategies(sourceViewer, contentType);
		}
	}
	
	@Override
	public String[] getIndentPrefixes(ISourceViewer sourceViewer, String contentType) {
		
		IndentMode indentMode = CodeFormatterConstants.IndentMode.fromPrefStore();
		int spaceIndentationSize = CodeFormatterConstants.FORMATTER_INDENTATION_SPACES_SIZE.get();
		String spaceIndent = AutoEditUtils.getNSpaces(spaceIndentationSize);
		
		switch (indentMode) {
		case TAB: return array("\t", spaceIndent); // return getIndentPrefixesForTab(spaceIndent); 
		case SPACES: return array(spaceIndent, "\t"); // return getIndentPrefixesForSpaces(spaceIndent);
		}
		
		throw assertUnreachable();
	}
	
	@Override
	protected void updateIndentationSettings(SourceViewer sourceViewer, String property) {
		super.updateIndentationSettings(sourceViewer, property);
		
		if(
			CodeFormatterConstants.FORMATTER_INDENTATION_SPACES_SIZE.keyEquals(property) ||
			CodeFormatterConstants.FORMATTER_INDENT_MODE.keyEquals(property)) {
			
			for(String contentType : getConfiguredContentTypes(sourceViewer)) {
				String[] prefixes= getIndentPrefixes(sourceViewer, contentType);
				sourceViewer.setIndentPrefixes(prefixes, contentType);
			}
		}
	}
	
	/* ----------------- Content Assist ----------------- */
	
	@Override
	public ContentAssistant getContentAssistant(ISourceViewer sourceViewer) {
		AbstractLangEditor editor = getEditor_asLang();
		if(editor != null) {
			final ContentAssistPreferenceHandler caPrefHelper = createContentAssistPrefHandler();
			
			ContentAssistantExt assistant = new ContentAssistantExt(caPrefHelper);
			assistant.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
			
			assistant.setRestoreCompletionProposalSize(LangUIPlugin.getDialogSettings("completion_proposal_size"));
			assistant.setInformationControlCreator(
				getInformationControl_ContentAsssist(caPrefHelper.getAdditionalInfoAffordanceString()));
			assistant.setContextInformationPopupOrientation(IContentAssistant.CONTEXT_INFO_ABOVE);
			assistant.enableColoredLabels(true);
			
			configureContentAssistantProcessors(assistant);
			// Note: configuration must come after processors are created
			assistant.configure(fPreferenceStore);
			
			return assistant;
		}
		
		return null;
	}
	
	protected ContentAssistPreferenceHandler createContentAssistPrefHandler() {
		return new ContentAssistPreferenceHandler();
	}
	
	protected void configureContentAssistantProcessors(ContentAssistant assistant) {
		Indexable<CompletionProposalsGrouping> categories = getContentAssistCategoriesProvider().getCategories();
		IContentAssistProcessor cap = createContentAssistProcessor(assistant, categories);
		assistant.setContentAssistProcessor(cap, IDocument.DEFAULT_CONTENT_TYPE);
	}
	
	protected LangContentAssistProcessor createContentAssistProcessor(ContentAssistant assistant,
			Indexable<CompletionProposalsGrouping> categories) {
		return new LangContentAssistProcessor(assistant, getEditor(), categories);
	}
	
	protected abstract ContentAssistCategoriesBuilder getContentAssistCategoriesProvider();
	
	
	/* ----------------- reconciler ----------------- */ 
	
	@Override
	public IReconciler getReconciler(ISourceViewer sourceViewer) {
		ITextEditor editor = getEditor();
		if(editor != null && editor.isEditable()) {
			AbstractReconciler reconciler = doCreateReconciler(editor);
			reconciler.setIsAllowedToModifyDocument(false);
			reconciler.setDelay(500); // Can't use zero
			
			return reconciler;
		}
		return null;
	}
	
	protected LangReconciler doCreateReconciler(ITextEditor editor) {
		CompositeReconcilingStrategy strategy = getReconciler_createCompositeStrategy(editor);
		return new LangReconciler(strategy, false, editor);
	}
	
	@SuppressWarnings("unused")
	protected CompositeReconcilingStrategy getReconciler_createCompositeStrategy(ITextEditor editor) {
		return new CompositeReconcilingStrategy();
	}
	
	/* -----------------  ----------------- */
	
	@Override
	public void setupCustomConfiguration(LangSourceViewer sourceViewer) {
		installOutlinePresenter(sourceViewer);
	}
	
}