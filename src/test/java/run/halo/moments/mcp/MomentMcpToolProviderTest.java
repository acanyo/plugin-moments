package run.halo.moments.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.moments.ListedMoment;
import run.halo.moments.Moment;
import run.halo.moments.exception.NotFoundException;
import run.halo.moments.service.MomentService;

class MomentMcpToolProviderTest {

    private ReactiveExtensionClient client;
    private MomentService momentService;
    private MomentMcpToolProvider provider;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        momentService = mock(MomentService.class);
        provider = new MomentMcpToolProvider(client, momentService);
    }

    @Test
    void shouldExposeFourLocalTools() {
        assertThat(provider.tools().map(McpToolDefinition::name).collectList().block())
            .containsExactly("list_moments", "get_moment", "create_moment", "delete_moment");
    }

    @Test
    void shouldDeclareOutputSchemas() {
        assertOutputSchema("list_moments", "page", "size", "total", "items");
        assertOutputSchema("get_moment", "moment", "owner", "stats");
        assertOutputSchema(
            "create_moment",
            "name", "content", "media", "tags", "visible", "owner", "approved",
            "releaseTime", "permalink", "emotion", "followUps");
        assertOutputSchema(
            "delete_moment",
            "name", "content", "media", "tags", "visible", "owner", "approved",
            "releaseTime", "permalink", "emotion", "followUps");

        var listItems = propertySchema(tool("list_moments").outputSchema(), "items");
        assertMomentSchema((Map<?, ?>) listItems.get("items"));

        var getSchema = tool("get_moment").outputSchema();
        assertMomentSchema(propertySchema(getSchema, "moment"));
        assertClosedObjectSchema(
            propertySchema(getSchema, "owner"), "displayName", "avatar", "name");
        assertClosedObjectSchema(
            propertySchema(getSchema, "stats"),
            "upvote", "totalComment", "approvedComment");

        assertMomentSchema(tool("create_moment").outputSchema());
        assertMomentSchema(tool("delete_moment").outputSchema());
    }

    @Test
    void shouldMarkDeleteAsDestructiveAndRequireConfirmation() {
        var tool = tool("delete_moment");

        assertThat(tool.annotations().destructiveHint()).isTrue();
        assertThat(tool.description()).contains("MUST obtain the user's explicit confirmation");
    }

    @Test
    void shouldDeleteMomentByName() {
        var moment = new Moment();
        when(client.fetch(Moment.class, "moment-1")).thenReturn(Mono.just(moment));
        when(momentService.deleteBy(moment)).thenReturn(Mono.just(moment));

        var result = tool("delete_moment").handler()
            .execute(new McpToolInvocation("delete_moment", Map.of("name", "moment-1")))
            .block();

        verify(momentService).deleteBy(moment);
        assertThat(result.error()).isFalse();
        assertThat(result.textContent()).isEqualTo("Moment deleted");
    }

    @Test
    void shouldReturnNotFoundWhenDeletingMissingMoment() {
        when(client.fetch(Moment.class, "missing")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> tool("delete_moment").handler()
            .execute(new McpToolInvocation("delete_moment", Map.of("name", "missing")))
            .block())
            .isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void shouldListMomentsWithFiltersAndPagination() {
        var moment = new Moment();
        when(client.listBy(eq(Moment.class), any(ListOptions.class), any(PageRequest.class)))
            .thenReturn(Mono.just(new ListResult<>(2, 5, 6L, List.of(moment))));
        var invocation = new McpToolInvocation("list_moments", Map.of(
            "page", 2,
            "size", 5,
            "owner", "admin",
            "approved", true,
            "startDate", "2026-08-01T00:00:00Z"));

        var result = tool("list_moments").handler().execute(invocation).block();

        assertThat(result.error()).isFalse();
        assertThat(result.structuredContent())
            .containsEntry("page", 2)
            .containsEntry("size", 5)
            .containsEntry("total", 6L);
        assertThat((List<?>) result.structuredContent().get("items"))
            .singleElement()
            .isInstanceOf(Map.class);
        var options = ArgumentCaptor.forClass(ListOptions.class);
        var page = ArgumentCaptor.forClass(PageRequest.class);
        verify(client).listBy(eq(Moment.class), options.capture(), page.capture());
        assertThat(options.getValue().getFieldSelector()).isNotNull();
        assertThat(page.getValue().getPageNumber()).isEqualTo(2);
        assertThat(page.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void shouldGetMomentWithOwnerAndStats() {
        var listedMoment = ListedMoment.builder().moment(new Moment()).build();
        when(momentService.findMomentByName("moment-1")).thenReturn(Mono.just(listedMoment));

        var result = tool("get_moment").handler()
            .execute(new McpToolInvocation("get_moment", Map.of("name", "moment-1")))
            .block();

        assertThat(result.structuredContent()).containsOnlyKeys("moment", "owner", "stats");
        assertThat(result.structuredContent().get("moment")).isInstanceOf(Map.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"list_moments", "get_moment"})
    void shouldReturnRecordedEmotionAndOrderedFollowUps(String toolName) {
        var moment = new Moment();
        moment.setSpec(new Moment.MomentSpec());
        var emotion = new Moment.MomentEmotion();
        emotion.setMood("😊 开心");
        emotion.setLocation("北京市");
        emotion.setWeather("晴 · 22°C");
        emotion.setWeatherSource("open-meteo");
        moment.getSpec().setEmotion(emotion);
        var first = new Moment.MomentFollowUp();
        first.setId("first");
        first.setContent("资料已发布。\n感谢参与。");
        first.setCreatedAt(Instant.parse("2026-11-06T08:00:00Z"));
        var second = new Moment.MomentFollowUp();
        second.setId("second");
        second.setContent("下期活动安排已公布。");
        second.setCreatedAt(Instant.parse("2026-11-25T08:00:00Z"));
        moment.getSpec().setFollowUps(List.of(first, second));

        var payload = readMomentPayload(toolName, moment);

        assertThat(payload.get("emotion")).isEqualTo(Map.of(
            "mood", "😊 开心", "location", "北京市", "weather", "晴 · 22°C",
            "weatherSource", "open-meteo"));
        assertThat(payload.get("followUps")).isEqualTo(List.of(
            Map.of("id", "first", "content", first.getContent(),
                "createdAt", "2026-11-06T08:00:00Z"),
            Map.of("id", "second", "content", second.getContent(),
                "createdAt", "2026-11-25T08:00:00Z")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"list_moments", "get_moment"})
    void shouldReturnNullEmotionAndEmptyFollowUpsForOldMoments(String toolName) {
        var moment = new Moment();
        for (var spec : new Moment.MomentSpec[] {null, new Moment.MomentSpec()}) {
            moment.setSpec(spec);

            var payload = readMomentPayload(toolName, moment);

            assertThat(payload.containsKey("emotion")).isTrue();
            assertThat(payload.containsKey("followUps")).isTrue();
            assertThat(payload.get("emotion")).isNull();
            assertThat(payload.get("followUps")).isEqualTo(List.of());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"list_moments", "get_moment"})
    void shouldReturnPartialEmotionWithNullableFields(String toolName) {
        var moment = new Moment();
        moment.setSpec(new Moment.MomentSpec());
        var emotion = new Moment.MomentEmotion();
        emotion.setMood("😌 平静");
        moment.getSpec().setEmotion(emotion);
        moment.getSpec().setFollowUps(List.of());

        var payload = readMomentPayload(toolName, moment);
        var actual = (Map<?, ?>) payload.get("emotion");

        assertThat(actual.keySet()).isEqualTo(Set.of("mood", "location", "weather", "weatherSource"));
        assertThat(actual.get("mood")).isEqualTo("😌 平静");
        assertThat(actual.get("location")).isNull();
        assertThat(actual.get("weather")).isNull();
        assertThat(actual.get("weatherSource")).isNull();
        assertThat(payload.get("followUps")).isEqualTo(List.of());
    }

    @Test
    void shouldReturnStableNotFoundError() {
        when(momentService.findMomentByName("missing"))
            .thenReturn(Mono.error(new NotFoundException("Moment not found.")));

        assertThatThrownBy(() -> tool("get_moment").handler()
            .execute(new McpToolInvocation("get_moment", Map.of("name", "missing")))
            .block())
            .isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void shouldCreateApprovedMomentFromPlainText() {
        when(momentService.create(any(Moment.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var invocation = new McpToolInvocation("create_moment", Map.of(
            "content", "<script>alert('x')</script>\nHello",
            "tags", List.of("Halo", "MCP"),
            "visible", "PRIVATE",
            "media", List.of(Map.of(
                "type", "PHOTO",
                "url", "/upload/photo.jpg",
                "originType", "image/jpeg"))));

        var result = tool("create_moment").handler().execute(invocation).block();

        var created = ArgumentCaptor.forClass(Moment.class);
        verify(momentService).create(created.capture());
        var moment = created.getValue();
        assertThat(moment.getMetadata().getGenerateName()).isEqualTo("moment-");
        assertThat(moment.getSpec().getContent().getRaw())
            .isEqualTo(moment.getSpec().getContent().getHtml())
            .contains("&lt;script&gt;")
            .doesNotContain("<script>");
        assertThat(moment.getSpec().getContent().getMedium()).hasSize(1);
        assertThat(moment.getSpec().getVisible()).isEqualTo(Moment.MomentVisible.PRIVATE);
        assertThat(moment.getSpec().getTags()).containsExactly("Halo", "MCP");
        assertThat(moment.getSpec().getApproved()).isTrue();
        assertThat(moment.getSpec().getApprovedTime()).isNotNull();
        assertThat(result.structuredContent())
            .containsEntry("content", moment.getSpec().getContent().getHtml())
            .containsEntry("visible", "PRIVATE")
            .containsEntry("approved", true);
    }

    @Test
    void shouldRejectEmptyMoment() {
        assertThatThrownBy(() -> tool("create_moment").handler()
            .execute(new McpToolInvocation("create_moment", Map.of()))
            .block())
            .isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_ARGUMENT"));
    }

    @Test
    void shouldAllowEmptyContentWithMedia() {
        when(momentService.create(any(Moment.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var invocation = new McpToolInvocation("create_moment", Map.of(
            "content", "",
            "media", List.of(Map.of("type", "PHOTO", "url", "/upload/photo.jpg"))));

        var result = tool("create_moment").handler().execute(invocation).block();

        assertThat(result.structuredContent()).containsEntry("content", "");
    }

    @Test
    void shouldRejectUnsafeMediaUrl() {
        var invocation = new McpToolInvocation("create_moment", Map.of(
            "media", List.of(Map.of("type", "PHOTO", "url", "javascript:alert(1)"))));

        assertThatThrownBy(() -> tool("create_moment").handler().execute(invocation).block())
            .isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_ARGUMENT"));
    }

    @Test
    void shouldNotLoadProviderWithoutMcpServerApi() {
        new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader("run.halo.mcpserver.api"))
            .withUserConfiguration(McpToolConfiguration.class)
            .run(context -> assertThat(context).doesNotHaveBean("momentMcpToolProvider"));
    }

    private McpToolDefinition tool(String name) {
        return provider.tools().filter(tool -> tool.name().equals(name)).blockFirst();
    }

    private Map<?, ?> readMomentPayload(String toolName, Moment moment) {
        if (toolName.equals("list_moments")) {
            when(client.listBy(eq(Moment.class), any(ListOptions.class), any(PageRequest.class)))
                .thenReturn(Mono.just(new ListResult<>(1, 20, 1L, List.of(moment))));
            var result = tool(toolName).handler()
                .execute(new McpToolInvocation(toolName, Map.of())).block();
            return (Map<?, ?>) ((List<?>) result.structuredContent().get("items")).getFirst();
        }
        when(momentService.findMomentByName("moment-1"))
            .thenReturn(Mono.just(ListedMoment.builder().moment(moment).build()));
        var result = tool(toolName).handler()
            .execute(new McpToolInvocation(toolName, Map.of("name", "moment-1"))).block();
        return (Map<?, ?>) result.structuredContent().get("moment");
    }

    private void assertOutputSchema(String toolName, String... propertyNames) {
        var schema = tool(toolName).outputSchema();
        assertThat(schema).containsEntry("type", "object");
        var actualProperties = ((Map<?, ?>) schema.get("properties")).keySet().stream()
            .map(String::valueOf)
            .toList();
        assertThat(actualProperties).containsExactlyInAnyOrder(propertyNames);
        assertThat(schema.get("required")).isEqualTo(List.of(propertyNames));
        assertThat(schema).containsEntry("additionalProperties", false);
    }

    private void assertMomentSchema(Map<?, ?> schema) {
        assertClosedObjectSchema(
            schema,
            "name", "content", "media", "tags", "visible", "owner", "approved",
            "releaseTime", "permalink", "emotion", "followUps");
        var media = propertySchema(schema, "media");
        assertClosedObjectSchema(
            (Map<?, ?>) media.get("items"), "type", "url", "originType");
        var emotion = propertySchema(schema, "emotion");
        assertThat(emotion.get("type")).isEqualTo(List.of("object", "null"));
        assertClosedObjectSchema(emotion, "mood", "location", "weather", "weatherSource");
        for (var field : List.of("mood", "location", "weather", "weatherSource")) {
            assertThat(propertySchema(emotion, field).get("type"))
                .isEqualTo(List.of("string", "null"));
        }
        var followUps = propertySchema(schema, "followUps");
        assertThat(followUps.get("type")).isEqualTo("array");
        var followUp = (Map<?, ?>) followUps.get("items");
        assertClosedObjectSchema(followUp, "id", "content", "createdAt");
        assertThat(propertySchema(followUp, "createdAt").get("format")).isEqualTo("date-time");
    }

    private void assertClosedObjectSchema(Map<?, ?> schema, String... propertyNames) {
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        var actualProperties = ((Map<?, ?>) schema.get("properties")).keySet().stream()
            .map(String::valueOf)
            .toList();
        assertThat(actualProperties).containsExactlyInAnyOrder(propertyNames);
        assertThat(schema.get("required")).isEqualTo(List.of(propertyNames));
    }

    private Map<?, ?> propertySchema(Map<?, ?> schema, String propertyName) {
        return (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get(propertyName);
    }
}
