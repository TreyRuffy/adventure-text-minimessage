/*
 * This file is part of adventure-text-minimessage, licensed under the MIT License.
 *
 * Copyright (c) 2018-2021 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.adventure.text.minimessage.transformation.inbuild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.Tokens;
import net.kyori.adventure.text.minimessage.parser.ParsingException;
import net.kyori.adventure.text.minimessage.parser.node.ElementNode;
import net.kyori.adventure.text.minimessage.parser.node.TagPart;
import net.kyori.adventure.text.minimessage.parser.node.ValueNode;
import net.kyori.adventure.text.minimessage.transformation.Modifying;
import net.kyori.adventure.text.minimessage.transformation.Transformation;
import net.kyori.adventure.text.minimessage.transformation.TransformationParser;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.examination.ExaminableProperty;
import org.jetbrains.annotations.NotNull;

/**
 * A transformation that applies a colour gradient.
 *
 * @since 4.1.0
 */
public final class GradientTransformation extends Transformation implements Modifying {

  private int size = -1;
  private int disableApplyingColorDepth = -1;

  private int index = 0;
  private int colorIndex = 0;

  private float factorStep = 0;
  private TextColor[] colors;
  private float phase = 0;
  private boolean negativePhase = false;

  /**
   * Get if this transformation can handle the provided tag name.
   *
   * @param name tag name to test
   * @return if this transformation is applicable
   * @since 4.1.0
   */
  public static boolean canParse(final String name) {
    return name.equalsIgnoreCase(Tokens.GRADIENT);
  }

  private GradientTransformation() {
  }

  @Override
  public void load(final String name, final List<TagPart> args) {
    super.load(name, args);

    if (!args.isEmpty()) {
      final List<TextColor> textColors = new ArrayList<>();
      for (int i = 0; i < args.size(); i++) {
        final String arg = args.get(i).value();
        // last argument? maybe this is the phase?
        if (i == args.size() - 1) {
          try {
            this.phase = Float.parseFloat(arg);
            if (this.phase < -1f || this.phase > 1f) {
              throw new ParsingException(String.format("Gradient phase is out of range (%s). Must be in the range [-1.0f, 1.0f] (inclusive).", this.phase), this.argTokenArray());
            }
            if (this.phase < 0) {
              this.negativePhase = true;
              this.phase = 1 + this.phase;
            }
            break;
          } catch (final NumberFormatException ignored) {
          }
        }

        final TextColor parsedColor;
        if (arg.charAt(0) == '#') {
          parsedColor = TextColor.fromHexString(arg);
        } else {
          parsedColor = NamedTextColor.NAMES.value(arg.toLowerCase(Locale.ROOT));
        }
        if (parsedColor == null) {
          throw new ParsingException(String.format("Unable to parse a color from '%s'. Please use NamedTextColors or Hex colors.", arg), this.argTokenArray());
        }
        textColors.add(parsedColor);
      }
      if (textColors.size() < 2) {
        throw new ParsingException("Invalid gradient, not enough colors. Gradients must have at least two colors.", this.argTokenArray());
      }
      this.colors = textColors.toArray(new TextColor[0]);
      if (this.negativePhase) {
        Collections.reverse(Arrays.asList(this.colors));
      }
    } else {
      this.colors = new TextColor[]{TextColor.fromHexString("#ffffff"), TextColor.fromHexString("#000000")};
    }
  }

  @Override
  public void visit(final ElementNode curr) {
    if (curr instanceof ValueNode) {
      final String value = ((ValueNode) curr).value();
      this.size += value.codePointCount(0, value.length());
    }
  }

  @Override
  public Component apply() {
    // init
    final int sectorLength = this.size / (this.colors.length - 1);
    this.factorStep = 1.0f / (sectorLength + this.index);
    this.phase = this.phase * sectorLength;
    this.index = 0;

    return Component.empty();
  }

  @Override
  public Component apply(final Component current, final int depth) {
    if (this.size == -1) {
      // we didn't init yet, cause at least part of current was a template, so let's do that now
      String content = PlainTextComponentSerializer.plainText().serialize(current);
      this.size = content.codePointCount(0, content.length());
      if (this.size == 0 || this.size == -1) {
        throw new ParsingException("Content of gradient seems strange, don't know how to calculate size!!");
      }
      apply();
    }

    if ((this.disableApplyingColorDepth != -1 && depth > this.disableApplyingColorDepth) || current.style().color() != null) {
      if (this.disableApplyingColorDepth == -1) {
        this.disableApplyingColorDepth = depth;
      }
      // This component has it's own color applied, which overrides ours
      // We still want to keep track of where we are though if this is text
      if (current instanceof TextComponent) {
        final String content = ((TextComponent) current).content();
        final int len = content.codePointCount(0, content.length());
        for (int i = 0; i < len; i++) {
          // increment our color index
          this.color();
        }
      }
      return current.children(Collections.emptyList());
    }

    if (current instanceof TextComponent && ((TextComponent) current).content().length() > 0) {
      final TextComponent textComponent = (TextComponent) current;
      final String content = textComponent.content();

      final TextComponent.Builder parent = Component.text();

      // apply
      final int[] holder = new int[1];
      for (final PrimitiveIterator.OfInt it = content.codePoints().iterator(); it.hasNext();) {
        holder[0] = it.nextInt();
        final Component comp = Component.text(new String(holder, 0, 1), this.color());
        parent.append(comp);
      }

      return parent.build();
    }

    return Component.empty().mergeStyle(current);
  }

  private TextColor color() {
    // color switch needed?
    if (this.factorStep * this.index > 1) {
      this.colorIndex++;
      this.index = 0;
    }

    float factor = this.factorStep * (this.index++ + this.phase);
    // loop around if needed
    if (factor > 1) {
      factor = 1 - (factor - 1);
    }

    if (this.negativePhase && this.colors.length % 2 != 0) {
      // flip the gradient segment for to allow for looping phase -1 through 1
      return this.interpolate(this.colors[this.colorIndex + 1], this.colors[this.colorIndex], factor);
    } else {
      return this.interpolate(this.colors[this.colorIndex], this.colors[this.colorIndex + 1], factor);
    }
  }

  private TextColor interpolate(final TextColor color1, final TextColor color2, final float factor) {
    return TextColor.color(
      Math.round(color1.red() + factor * (color2.red() - color1.red())),
      Math.round(color1.green() + factor * (color2.green() - color1.green())),
      Math.round(color1.blue() + factor * (color2.blue() - color1.blue()))
    );
  }

  @Override
  public @NotNull Stream<? extends ExaminableProperty> examinableProperties() {
    return Stream.of(
      ExaminableProperty.of("phase", this.phase),
      ExaminableProperty.of("colors", this.colors)
    );
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) return true;
    if (other == null || this.getClass() != other.getClass()) return false;
    final GradientTransformation that = (GradientTransformation) other;
    return this.index == that.index
      && this.colorIndex == that.colorIndex
      && Float.compare(that.factorStep, this.factorStep) == 0
      && this.phase == that.phase && Arrays.equals(this.colors, that.colors);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(this.index, this.colorIndex, this.factorStep, this.phase);
    result = 31 * result + Arrays.hashCode(this.colors);
    return result;
  }

  /**
   * Factory for {@link GradientTransformation} instances.
   *
   * @since 4.1.0
   */
  public static class Parser implements TransformationParser<GradientTransformation> {
    @Override
    public GradientTransformation parse() {
      return new GradientTransformation();
    }
  }
}
