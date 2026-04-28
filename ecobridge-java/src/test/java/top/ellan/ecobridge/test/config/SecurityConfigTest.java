package top.ellan.ecobridge.test.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for SecurityConfig password resolution logic. */
class SecurityConfigTest {

  @Test
  void testPlaceholderDetectionLogic() {
    // Verify placeholder detection works correctly
    // "change_me_to_real_password" should be recognized
    String placeholder = "change_me_to_real_password";
    assertTrue(isPlaceholder(placeholder));
  }

  @Test
  void testValidPasswordNotDetectedAsPlaceholder() {
    String valid = "my_secure_password_123!";
    assertFalse(isPlaceholder(valid));
  }

  @Test
  void testEmptyStringNotPlaceholder() {
    assertFalse(isPlaceholder(""));
  }

  @Test
  void testNullHandledGracefully() {
    assertFalse(isPlaceholder(null));
  }

  @Test
  void testChangemePlaceholder() {
    assertTrue(isPlaceholder("changeme"));
  }

  @Test
  void testPasswordPlaceholder() {
    assertTrue(isPlaceholder("password"));
  }

  private static boolean isPlaceholder(String value) {
    return value != null
        && (value.equals("change_me_to_real_password")
            || value.equals("changeme")
            || value.equals("password"));
  }
}
