package haveno.price;

import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ApiController {

    private final Environment env;

    @GetMapping("/version")
    public Map<String, String> getVersion() {
        Map<String, String> response = new HashMap<>();
        response.put("version", env.getProperty("haveno.price.version", ""));
        return response;
    }
}
