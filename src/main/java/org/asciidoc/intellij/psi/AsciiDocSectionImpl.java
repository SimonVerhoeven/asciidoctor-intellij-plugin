package org.asciidoc.intellij.psi;

import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.stubs.IStubElementType;
import icons.AsciiDocIcons;
import org.asciidoc.intellij.inspections.AsciiDocVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author yole
 */
public class AsciiDocSectionImpl extends AsciiDocSectionStubElementImpl<AsciiDocSectionStub> implements AsciiDocSelfDescribe, AsciiDocSection {
  public AsciiDocSectionImpl(AsciiDocSectionStub stub, IStubElementType nodeType) {
    super(stub, nodeType);
  }

  public AsciiDocSectionImpl(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  @NotNull
  public String getTitle() {
    final AsciiDocSectionStub stub = getGreenStub();
    if (stub != null && !AsciiDocUtil.ATTRIBUTES.matcher(stub.getTitleNoSubstitution()).find()) {
      return stub.getTitleNoSubstitution();
    }
    AsciiDocHeading heading = findChildByClass(AsciiDocHeading.class);
    if (heading != null) {
      return heading.getHeadingText(true);
    }
    return "<untitled>";
  }

  /**
   * Return the title without substituting attributes. Use this to build a stub index, as otherwise
   * the IDE would report "Reentrant indexing", and the index would not update when attributes update.
   */
  @NotNull
  public String getTitleNoSubstitution() {
    final AsciiDocSectionStub stub = getGreenStub();
    if (stub != null) {
      return stub.getTitleNoSubstitution();
    }
    AsciiDocHeading heading = findChildByClass(AsciiDocHeading.class);
    if (heading != null) {
      return heading.getHeadingText(false);
    }
    return "<untitled>";
  }

  // taken from Asciidoctor (rx.rb#InvalidSectionIdCharsRx)
  public static final Pattern INVALID_SECTION_ID_CHARS = Pattern.compile("(?U)<[^>]+>|&(?:[a-z][a-z]+\\d{0,2}|#\\d\\d\\d{0,4}|#x[\\da-f][\\da-f][\\da-f]{0,3});|[^ \\w\\-.]+?");

  /**
   * Produces the ID from a section like Asciidoctor (section.rb#generate_id).
   * If there are duplicate IDs in the rendered document, they receive a suffix (_num); this is not included here.
   */
  @Override
  public String getAutogeneratedId() {
    // remove invalid characters and add prefix
    String idPrefix = getAttribute("idprefix", "_");
    String idSeparator = getAttribute("idseparator", "_");
    String key = idPrefix + INVALID_SECTION_ID_CHARS.matcher(getTitle().toLowerCase(Locale.US)).replaceAll("");
    // transform some characters to separator
    key = key.replaceAll("[ .-]", Matcher.quoteReplacement(idSeparator));
    // remove duplicates separators
    key = key.replaceAll(idSeparator + idSeparator, Matcher.quoteReplacement(idSeparator));
    // remove separator at end
    key = StringUtil.trimEnd(key, Matcher.quoteReplacement(idSeparator));
    return key;
  }

  @SuppressWarnings("SameParameterValue")
  @TestOnly
  protected String getAttribute(String attr, String defaultVal) {
    String val = defaultVal;
    List<AsciiDocAttributeDeclaration> idPrefixDecl = AsciiDocUtil.findAttributes(this.getProject(), attr);
    for (AsciiDocAttributeDeclaration asciiDocAttributeDeclaration : idPrefixDecl) {
      if (asciiDocAttributeDeclaration.getAttributeValue() != null) {
        val = asciiDocAttributeDeclaration.getAttributeValue();
        break;
      } else {
        val = "";
      }
    }
    return val;
  }

  @Nullable
  @Override
  public AsciiDocBlockId getBlockId() {
    PsiElement child = this.getFirstChild();
    while (child != null) {
      if (child instanceof AsciiDocBlockId) {
        return (AsciiDocBlockId) child;
      }
      if (child instanceof AsciiDocHeading) {
        return ((AsciiDocHeading) child).getBlockId();
      }
      child = child.getNextSibling();
    }
    return null;
  }

  @Override
  public String getAttribute(String name) {
    for (PsiElement child : this.getChildren()) {
      if (child instanceof AsciiDocBlockAttributes) {
        return ((AsciiDocBlockAttributes) child).getAttribute(name);
      }
      if (child instanceof AsciiDocHeading) {
        break;
      }
    }
    return null;
  }

  /**
   * Compare a ID to the automatically generated ID of this section. Will ignore any numeric suffix in the ID.
   */
  @Override
  public boolean matchesAutogeneratedId(String keyToCompare) {
    String ownKey = getAutogeneratedId();
    if (keyToCompare.length() < ownKey.length()) {
      return false;
    }
    if (!keyToCompare.startsWith(ownKey)) {
      return false;
    }
    if (keyToCompare.length() == ownKey.length()) {
      return true;
    }
    //noinspection RedundantIfStatement
    if (keyToCompare.substring(ownKey.length()).matches("^_[0-9]*$")) {
      return true;
    }
    return false;
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof AsciiDocVisitor) {
      ((AsciiDocVisitor) visitor).visitSections(this);
      return;
    }

    super.accept(visitor);
  }

  @Override
  public String getName() {
    // must not return title with substitution, as this would confuse IntelliJ when it re-constructs the PSIStub
    // and it might throw an exception "PSI and index do not match."
    return getTitleNoSubstitution();
  }

  @Override
  public ItemPresentation getPresentation() {
    return AsciiDocPsiImplUtil.getPresentation(this);
  }

  @Override
  public Icon getIcon(int ignored) {
    return AsciiDocIcons.Structure.SECTION;
  }

  @NotNull
  @Override
  public String getDescription() {
    return getTitle();
  }

  @NotNull
  @Override
  public String getFoldedSummary() {
    AsciiDocHeading heading = findChildByClass(AsciiDocHeading.class);
    if (heading == null) {
      throw new IllegalStateException("section without heading");
    }
    return heading.getText();
  }

  @Override
  public int getHeadingLevel() {
    AsciiDocHeading heading = findChildByClass(AsciiDocHeading.class);
    if (heading == null) {
      throw new IllegalStateException("section without heading");
    }
    return heading.getHeadingLevel();
  }

  public PsiElement getHeadingElement() {
    AsciiDocHeading heading = findChildByClass(AsciiDocHeading.class);
    if (heading == null) {
      throw new IllegalStateException("section without heading");
    }
    return heading;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + getNode().getElementType().toString() + ")";
  }

}
