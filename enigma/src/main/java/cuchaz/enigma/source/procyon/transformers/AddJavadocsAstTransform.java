package cuchaz.enigma.source.procyon.transformers;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.strobel.assembler.metadata.ParameterDefinition;
import com.strobel.decompiler.languages.java.ast.*;
import com.strobel.decompiler.languages.java.ast.transforms.IAstTransform;
import cuchaz.enigma.source.procyon.EntryParser;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.ResolutionStrategy;
import cuchaz.enigma.translation.representation.entry.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class AddJavadocsAstTransform implements IAstTransform {

	private final EntryRemapper remapper;

	public AddJavadocsAstTransform(EntryRemapper remapper) {
		this.remapper = remapper;
	}

	@Override
	public void run(AstNode compilationUnit) {
		compilationUnit.acceptVisitor(new Visitor(remapper), null);
	}

	static class Visitor extends DepthFirstAstVisitor<Void, Void> {

		private final EntryRemapper remapper;

		Visitor(EntryRemapper remapper) {
			this.remapper = remapper;
		}

		private <T extends AstNode> void addDoc(T node, Function<T, Entry<?>> retriever) {
			final Comment[] comments = getComments(node, retriever);
			if (comments != null) {
				node.insertChildrenBefore(node.getFirstChild(), Roles.COMMENT, comments);
			}
		}

		private <T extends AstNode> Comment[] getComments(T node, Function<T, Entry<?>> retriever) {
			final EntryMapping mapping = remapper.getDeobfMapping(retriever.apply(node));
			final String docs = mapping == null ? null : Strings.emptyToNull(mapping.getJavadoc());
			return docs == null ? null : Stream.of(docs.split("\\R")).map(st -> new Comment(st,
					CommentType.Documentation)).toArray(Comment[]::new);
		}

		private Comment[] getParameterComments(ParameterDeclaration node, Function<ParameterDeclaration, Entry<?>> retriever) {
			final EntryMapping mapping = remapper.getDeobfMapping(retriever.apply(node));
			final Comment[] ret = getComments(node, retriever);
			if (ret != null) {
				final String paramPrefix = "@param " + mapping.getTargetName() + " ";
				final String indent = Strings.repeat(" ", paramPrefix.length());
				ret[0].setContent(paramPrefix + ret[0].getContent());
				for (int i = 1; i < ret.length; i++) {
					ret[i].setContent(indent + ret[i].getContent());
				}
			}
			return ret;
		}

		private void visitMethod(AstNode node) {
			final MethodDefEntry methodDefEntry = EntryParser.parse(node.getUserData(Keys.METHOD_DEFINITION));
			final Comment[] baseComments = getComments(node, $ -> methodDefEntry);
			List<Comment> comments = new ArrayList<>();
			if (baseComments != null)
				Collections.addAll(comments, baseComments);

			for (ParameterDeclaration dec : node.getChildrenByRole(Roles.PARAMETER)) {
				ParameterDefinition def = dec.getUserData(Keys.PARAMETER_DEFINITION);
				final Comment[] paramComments = getParameterComments(dec, $ -> new LocalVariableDefEntry(methodDefEntry, def.getSlot(), def.getName(),
						true,
						EntryParser.parseTypeDescriptor(def.getParameterType()), null));
				if (paramComments != null)
					Collections.addAll(comments, paramComments);
			}

			if (!comments.isEmpty()) {
				if (remapper.getObfResolver().resolveEntry(methodDefEntry, ResolutionStrategy.RESOLVE_ROOT).stream().noneMatch(e -> Objects.equals(e, methodDefEntry))) {
					comments.add(0, new Comment("{@inheritDoc}", CommentType.Documentation));
				}
				final AstNode oldFirst = node.getFirstChild();
				for (Comment comment : comments) {
					node.insertChildBefore(oldFirst, comment, Roles.COMMENT);
				}
			}
		}

		@Override
		protected Void visitChildren(AstNode node, Void data) {
			for (final AstNode child : node.getChildren()) {
				child.acceptVisitor(this, data);
			}
			return null;
		}

		@Override
		public Void visitMethodDeclaration(MethodDeclaration node, Void data) {
			visitMethod(node);
			return super.visitMethodDeclaration(node, data);
		}

		@Override
		public Void visitConstructorDeclaration(ConstructorDeclaration node, Void data) {
			visitMethod(node);
			return super.visitConstructorDeclaration(node, data);
		}

		@Override
		public Void visitFieldDeclaration(FieldDeclaration node, Void data) {
			addDoc(node, dec -> EntryParser.parse(dec.getUserData(Keys.FIELD_DEFINITION)));
			return super.visitFieldDeclaration(node, data);
		}

		@Override
		public Void visitTypeDeclaration(TypeDeclaration node, Void data) {
			addDoc(node, dec -> EntryParser.parse(dec.getUserData(Keys.TYPE_DEFINITION)));
			return super.visitTypeDeclaration(node, data);
		}

		@Override
		public Void visitEnumValueDeclaration(EnumValueDeclaration node, Void data) {
			addDoc(node, dec -> EntryParser.parse(dec.getUserData(Keys.FIELD_DEFINITION)));
			return super.visitEnumValueDeclaration(node, data);
		}
	}
}
