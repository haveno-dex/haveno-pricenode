package haveno.price;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiController {

    private static final String VERSION = readVersion();

    @GetMapping("/version")
    public Map<String, String> getVersion() {
        Map<String, String> response = new HashMap<>();
        response.put("version", VERSION);
        return response;
    }

    private static String readVersion() {
        try (InputStream in = new ClassPathResource("version.txt").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8).trim().replace("-SNAPSHOT", "");
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read version.txt", ex);
        }
    }
}
