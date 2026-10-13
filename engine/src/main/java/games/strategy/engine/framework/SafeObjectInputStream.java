package games.strategy.engine.framework;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.List;

/**
 * An {@link ObjectInputStream} that only resolves classes of the game engine, the JDK and Guava.
 * Save games are Java serialized object graphs; a crafted file could otherwise instantiate any
 * class on the class path ("deserialization gadgets"). Android has no {@code ObjectInputFilter},
 * so the allow list is enforced in {@link #resolveClass}. {@code java.awt} is not allowed: Android
 * has no AWT, so tests on the JVM must fail where the phone would.
 */
public class SafeObjectInputStream extends ObjectInputStream {
  private static final List<String> ALLOWED_PREFIXES =
      List.of(
          "games.strategy.",
          "org.triplea.",
          "java.lang.",
          "java.util.",
          "java.math.",
          "java.time.",
          "java.io.Serializable",
          "com.google.common.collect.",
          "com.google.common.base.");

  public SafeObjectInputStream(final InputStream in) throws IOException {
    super(in);
  }

  @Override
  protected Class<?> resolveClass(final ObjectStreamClass desc)
      throws IOException, ClassNotFoundException {
    final String name = desc.getName();
    if (!isAllowed(name)) {
      throw new InvalidClassException(name, "class not allowed in a save game");
    }
    return super.resolveClass(desc);
  }

  @Override
  protected Class<?> resolveProxyClass(final String[] interfaces) throws IOException {
    throw new InvalidClassException("proxy", "proxy classes not allowed in a save game");
  }

  static boolean isAllowed(final String className) {
    // arrays: "[Lgames.strategy.Foo;" / "[I"
    String name = className;
    while (name.startsWith("[")) {
      name = name.substring(1);
    }
    if (name.length() == 1) {
      return true; // primitive array element
    }
    if (name.startsWith("L") && name.endsWith(";")) {
      name = name.substring(1, name.length() - 1);
    }
    // the java.lang. prefix would otherwise admit SerializedLambda and reflection gadgets
    if (name.startsWith("java.lang.invoke.") || name.startsWith("java.lang.reflect.")) {
      return false;
    }
    for (final String prefix : ALLOWED_PREFIXES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
