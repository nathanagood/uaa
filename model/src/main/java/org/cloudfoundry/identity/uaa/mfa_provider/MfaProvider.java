package org.cloudfoundry.identity.uaa.mfa_provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.cloudfoundry.identity.uaa.util.JsonUtils.getNodeAsBoolean;
import static org.cloudfoundry.identity.uaa.util.JsonUtils.getNodeAsDate;
import static org.cloudfoundry.identity.uaa.util.JsonUtils.getNodeAsString;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = MfaProvider.MfaProviderDeserializer.class)
public class MfaProvider<T extends AbstractMfaProviderConfig> {

    public static final String FIELD_IDENTITY_ZONE_ID = "identityZoneId";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_ACTIVE = "active";
    public static final String FIELD_CREATED = "created";
    public static final String FIELD_LAST_MODIFIED = "last_modified";
    public static final String FIELD_ID = "id";



    private String id;
    private String name;
    private String identityZoneId;
    private boolean active = true;

    private AbstractMfaProviderConfig config;

    private MfaProviderType type;
    private Date created;
    @JsonProperty("last_modified")
    private Date lastModified;


    public Date getCreated() {
        return created;
    }

    public MfaProvider<T> setCreated(Date created) {
        this.created = created;
        return this;
    }

    public Date getLastModified() {
        return lastModified;
    }

    public MfaProvider<T> setLastModified(Date lastModified) {
        this.lastModified = lastModified;
        return this;
    }


    public String getIdentityZoneId() {
        return identityZoneId;
    }

    public MfaProvider<T> setIdentityZoneId(String identityZoneId) {
        this.identityZoneId = identityZoneId;
        return this;
    }

    public enum MfaProviderType {
        GOOGLE_AUTHENTICATOR;

        private static Map<String, MfaProviderType> namesMap = new HashMap<String, MfaProviderType>();
        static {
            namesMap.put("google-authenticator", GOOGLE_AUTHENTICATOR);
        }

        @JsonCreator
        public static MfaProviderType forValue(String value) {
            return namesMap.get(value);
        }

        @JsonValue
        public String toValue() {
            for (Map.Entry<String, MfaProviderType> entry : namesMap.entrySet()) {
                if (entry.getValue() == this)
                    return entry.getKey();
            }

            return null; // or fail
        }
    }

    public T getConfig() {
        return (T) config;
    }

    public MfaProvider<T> setConfig(T config) {
        this.config = config;
        return this;
    }

    public String getId() {
        return id;
    }

    public MfaProvider<T> setId(String id) {
        this.id = id;
        return this;
    }

    public Boolean isActive() {
        return active;
    }

    public MfaProvider setActive(boolean active) {
        this.active = active;
        return this;
    }

    public String getName() {
        return name;
    }

    public MfaProvider<T> setName(String name) {
        this.name = name;
        return this;
    }

    public MfaProviderType getType() {
        return type;
    }

    public MfaProvider<T> setType(MfaProviderType type) {
        this.type = type;
        return this;
    }

    public void validate() {
        if(StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Provider name must be set");
        }
        if(name.length() > 255 || !name.matches("^[a-zA-Z0-9]*$")){
            throw new IllegalArgumentException("Provider name invalid");
        }
        if(type == null) {
            throw new IllegalArgumentException("Provider type must be set");
        }
        if(config == null) {
            throw new IllegalArgumentException("Provider config must be set");
        }
        if(!StringUtils.hasText(identityZoneId)){
            throw new IllegalArgumentException("Provider must belong to a zone");
        }
        config.validate();
    }

    public static class MfaProviderDeserializer extends JsonDeserializer<MfaProvider> {

        @Override
        public MfaProvider deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            MfaProvider result =  new MfaProvider();

            JsonNode node = JsonUtils.readTree(p);
            MfaProviderType type;
            try {
                type = MfaProviderType.forValue(getNodeAsString(node, FIELD_TYPE, "google-authenticator"));
            } catch(IllegalArgumentException e) {
                type = null;
            }
            //deserialize based on type
            JsonNode configNode = node.get("config");
            String config = configNode != null ? (configNode.isTextual() ? configNode.textValue() : configNode.toString()) : null;
            AbstractMfaProviderConfig definition = null;
            if(type != null) {
                switch(type) {
                    case GOOGLE_AUTHENTICATOR:
                        definition = StringUtils.hasText(config) ? JsonUtils.readValue(config, GoogleMfaProviderConfig.class) : new GoogleMfaProviderConfig();
                        break;
                    default:
                        break;
                }
            }

            result.setConfig(definition);
            result.setType(type);
            result.setName(getNodeAsString(node, FIELD_NAME, null));
            result.setId(getNodeAsString(node, FIELD_ID, null));
            result.setActive(getNodeAsBoolean(node, FIELD_ACTIVE, true));
            result.setIdentityZoneId(getNodeAsString(node, FIELD_IDENTITY_ZONE_ID, null));
            result.setCreated(getNodeAsDate(node, FIELD_CREATED));
            result.setLastModified(getNodeAsDate(node, FIELD_LAST_MODIFIED));

            return result;
        }
    }

}

