package com.tduck.cloud.api.survey;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sun.net.httpserver.HttpServer;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.mybatis.handler.AutoFillMetaInfoHandler;
import com.tduck.cloud.common.sms.*;
import com.tduck.cloud.form.entity.*;
import com.tduck.cloud.form.entity.enums.FormStatusEnum;
import com.tduck.cloud.form.service.UserFormService;
import com.tduck.cloud.form.survey.*;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** 显式提供 SURVEY_TEST_JDBC_URL 才执行；只允许名称以 tduck_survey_test_ 开头的隔离MySQL库。 */
public class SurveyWorkflowDatabaseTest {
    private static AnnotationConfigApplicationContext context;
    private static HttpServer server;
    private static SmsPlatformProperties properties;
    private static final AtomicInteger calls = new AtomicInteger();
    private static volatile String response = "{\"code\":\"0000000\",\"taskid\":\"shared-task\"}";
    private static final List<Integer> requestSizes = new CopyOnWriteArrayList<>();
    private static volatile java.util.function.BiFunction<String, cn.hutool.json.JSONObject, String>
            responder;
    private String formKey;

    @BeforeAll
    public static void start() throws Exception {
        String url = System.getenv("SURVEY_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "需提供隔离MySQL测试库");
        assertTrue(
                url.matches("jdbc:mysql://[^/]+/tduck_survey_test_[A-Za-z0-9_]+(?:\\?.*)?"),
                "禁止对业务库执行集成测试");
        properties = new SmsPlatformProperties();
        properties.setType(SmsTypeEnum.YUNXIN);
        properties.setSurveyEnabled(true);
        properties.setYunxinUserId("000000000001");
        properties.setYunxinAccessNumber("10650001");
        properties.setYunxinExtendCode("000001");
        properties.setYunxinUsername("test");
        properties.setSurveyPublicBaseUrl("https://survey.example.test");
        properties.setSurveySendStartHour(0);
        properties.setSurveySendEndHour(24);
        properties.setSurveySendIntervalSeconds(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = exchange.getRequestBody().read(buf)) != -1) out.write(buf, 0, n);
                    cn.hutool.json.JSONObject body =
                            JSONUtil.parseObj(
                                    new String(out.toByteArray(), StandardCharsets.UTF_8));
                    requestSizes.add(
                            body.getJSONArray("varData") == null
                                    ? 0
                                    : body.getJSONArray("varData").size());
                    String bodyText =
                            responder == null
                                    ? response
                                    : responder.apply(exchange.getRequestURI().getPath(), body);
                    byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        properties.setYunxinBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        com.tduck.cloud.common.util.SpringContextUtils spring =
                new com.tduck.cloud.common.util.SpringContextUtils();
        spring.setApplicationContext(context);
        spring.postProcessBeanFactory(context.getBeanFactory());
        DataSource ds = context.getBean(DataSource.class);
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("doc/migrations")))
            root = root.getParent();
        assertNotNull(root);
        try (Connection c = ds.getConnection()) {
            List<Path> scripts = new ArrayList<>();
            try (java.util.stream.Stream<Path> paths = Files.list(root.resolve("doc/migrations"))) {
                paths.filter(p -> p.toString().endsWith(".sql")).sorted().forEach(scripts::add);
            }
            for (Path p : scripts)
                ScriptUtils.executeSqlScript(
                        c,
                        new org.springframework.core.io.support.EncodedResource(
                                new FileSystemResource(p.toFile()), "UTF-8"));
        }
        context.getBean(JdbcTemplate.class)
                .execute(
                        "CREATE TABLE IF NOT EXISTS fm_user_form_data(id BIGINT AUTO_INCREMENT"
                                + " PRIMARY KEY,form_key VARCHAR(64),create_time DATETIME DEFAULT"
                                + " CURRENT_TIMESTAMP)");
    }

    @AfterAll
    public static void stop() {
        if (context != null) context.close();
        if (server != null) server.stop(0);
    }

    @BeforeEach
    public void before() {
        formKey = "test" + UUID.randomUUID().toString().replace("-", "");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 1L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        calls.set(0);
        requestSizes.clear();
        responder = null;
        properties.setYunxinReceiptEnabled(false);
        properties.setYunxinReceiptProfile("DOCUMENT");
        properties.setYunxinQueryStartField("startTime");
        properties.setYunxinUpstreamEnabled(false);
        response = "{\"code\":\"0000000\",\"taskid\":\"shared-task\"}";
        context.getBean(JdbcTemplate.class).update("UPDATE fm_survey_suppression SET active=0");
    }

    @AfterEach
    public void after() {
        if (context != null)
            context.getBean(JdbcTemplate.class)
                    .update(
                            "UPDATE fm_survey_delivery SET delivery_status='TIMEOUT' WHERE"
                                    + " form_key=?",
                            formKey);
        if (context != null)
            context.getBean(JdbcTemplate.class)
                    .update(
                            "UPDATE fm_survey_batch SET status='CANCELED' WHERE form_key=? AND"
                                    + " status IN ('PENDING','RUNNING','PAUSED')",
                            formKey);
        RequestContextHolder.resetRequestAttributes();
    }

    private SurveyBatchRequest prepare(int count, String mode) {
        SurveySettingsService settings = context.getBean(SurveySettingsService.class);
        SurveySetting setting = settings.get(formKey);
        setting.setMode(mode);
        settings.save(setting);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("phone", String.valueOf(13800000000L + i));
            row.put("name", "测试" + i);
            rows.add(row);
        }
        context.getBean(SurveyRecipientService.class).confirm(formKey, rows, false);
        SurveySmsTemplate template = new SurveySmsTemplate();
        template.setName("测试邀请");
        template.setContent("【测试】https://survey.example.test/i/#params_1#");
        template.setVariable(true);
        template.setProviderTemplateId("test-template");
        template.setMappingJson("{\"p1\":{\"source\":\"INVITE_TOKEN\"}}");
        template = context.getBean(SurveyTemplateService.class).save(template);
        context.getBean(SurveySmsTemplateMapper.class)
                .update(
                        null,
                        new UpdateWrapper<SurveySmsTemplate>()
                                .eq("id", template.getId())
                                .set(
                                        "audit_json",
                                        "{\"rx_statu\":\"通过\",\"cmcc_statu\":\"正常可用\",\"cucc_statu\":\"正常可用\",\"ctcc_statu\":\"正常可用\"}"));
        SurveyBatchRequest request = new SurveyBatchRequest();
        request.setFormKey(formKey);
        request.setTemplateId(template.getId());
        request.setName("测试批次");
        request.setRequestKey(UUID.randomUUID().toString());
        request.setExpectedTemplateVersion(template.getVersion());
        return approve(request);
    }

    private SurveyBatchRequest approve(SurveyBatchRequest request) {
        Map<String, Object> preview = context.getBean(SurveyBatchService.class).preview(request);
        request.setRecipientIds((List<Long>) preview.get("eligibleIds"));
        request.setExpectedSelectionHash((String) preview.get("selectionHash"));
        return request;
    }

    @Test
    public void previewFreezesRecipientsAndRejectsChangedPersonalization() {
        SurveyBatchRequest request = prepare(2, "TRACKED");
        Map<String, Object> added = new HashMap<>();
        added.put("phone", "13999999999");
        context.getBean(SurveyRecipientService.class)
                .confirm(formKey, Collections.singletonList(added), false);
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(request);
        assertEquals(Integer.valueOf(2), batch.getRecipientCount());
        SurveyBatchRequest changed = prepare(1, "TRACKED");
        context.getBean(SurveyRecipientMapper.class)
                .update(
                        null,
                        new UpdateWrapper<SurveyRecipient>()
                                .eq("form_key", formKey)
                                .set("company", "资料已更新"));
        assertThrows(
                BaseException.class,
                () -> context.getBean(SurveyBatchService.class).create(changed));
    }

    @Test
    public void idempotencyModeLockAndRecipientPrivacy() {
        SurveyBatchService service = context.getBean(SurveyBatchService.class);
        SurveyBatchRequest request = prepare(3, "ANONYMOUS");
        SurveyBatch batch = service.create(request);
        assertEquals(batch.getId(), service.create(request).getId());
        request.setName("不同内容");
        assertThrows(BaseException.class, () -> service.create(request));
        SurveySetting setting = context.getBean(SurveySettingsService.class).get(formKey);
        setting.setMode("TRACKED");
        assertThrows(
                BaseException.class,
                () -> context.getBean(SurveySettingsService.class).save(setting));
        List<SurveyInvitation> invitations =
                context.getBean(SurveyInvitationMapper.class)
                        .selectList(
                                new QueryWrapper<SurveyInvitation>().eq("batch_id", batch.getId()));
        assertEquals(1, invitations.size());
        assertNull(invitations.get(0).getRecipientId());
        Map<String, Object> detail =
                service.deliveries(batch.getId(), 1, 100, null, null, null, null, null);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) detail.get("records")).get(0);
        assertFalse(first.containsKey("variablesJson"));
        assertTrue(
                service.recipientProgress(
                                formKey,
                                Collections.singletonList(
                                        ((Number) first.get("recipientId")).longValue()))
                        .isEmpty());
        Map<String, Object> publicContext =
                context.getBean(SurveyInvitationService.class)
                        .publicContext(invitations.get(0).getToken());
        assertEquals(
                new HashSet<>(Arrays.asList("formKey", "mode", "notice")), publicContext.keySet());
    }

    @Test
    public void workerSplitsAt100AndNeverResendsAcceptedRows() {
        SurveyBatch batch =
                context.getBean(SurveyBatchService.class).create(prepare(101, "TRACKED"));
        context.getBean(SurveyDispatchWorker.class).runOnce();
        assertEquals(Arrays.asList(100, 1), requestSizes);
        assertEquals(
                Long.valueOf(101),
                context.getBean(SurveyDeliveryMapper.class)
                        .selectCount(
                                new QueryWrapper<SurveyDelivery>()
                                        .eq("batch_id", batch.getId())
                                        .eq("submit_status", "ACCEPTED")));
        int before = calls.get();
        context.getBean(SurveyDispatchWorker.class).runOnce();
        assertEquals(before, calls.get());
    }

    @Test
    public void abandonedSubmissionBecomesUnknownAndIsNeverReplayed() {
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(prepare(1, "TRACKED"));
        SurveySendAttempt attempt = new SurveySendAttempt();
        attempt.setFormKey(formKey);
        attempt.setBatchId(batch.getId());
        attempt.setAccountKey(batch.getAccountKey());
        attempt.setState("SUBMITTING");
        attempt.setRequestDigest("test");
        attempt.setStartedAt(SurveyBatchService.now().minusMinutes(4));
        context.getBean(SurveySendAttemptMapper.class).insert(attempt);
        context.getBean(SurveyDeliveryMapper.class)
                .update(
                        null,
                        new UpdateWrapper<SurveyDelivery>()
                                .eq("batch_id", batch.getId())
                                .set("attempt_id", attempt.getId())
                                .set("submit_status", "SUBMITTING")
                                .set("submitted_at", attempt.getStartedAt()));
        context.getBean(SurveyDispatchWorker.class).runOnce();
        assertEquals(0, calls.get());
        assertEquals(
                "UNKNOWN",
                context.getBean(SurveySendAttemptMapper.class)
                        .selectById(attempt.getId())
                        .getState());
        context.getBean(SurveyDispatchWorker.class).runOnce();
        assertEquals(0, calls.get());
    }

    @Test
    public void databaseLeaseAllowsOnlyOneConcurrentOwner() throws Exception {
        SurveyDispatchLeaseMapper leases = context.getBean(SurveyDispatchLeaseMapper.class);
        String key = "test:" + UUID.randomUUID();
        leases.ensure(key);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Callable<Integer> contender =
                () -> {
                    gate.await();
                    return leases.claim(
                            key,
                            UUID.randomUUID().toString(),
                            SurveyBatchService.now(),
                            SurveyBatchService.now().plusMinutes(3));
                };
        Future<Integer> a = pool.submit(contender), b = pool.submit(contender);
        gate.countDown();
        assertEquals(1, a.get() + b.get());
        pool.shutdown();
    }

    @Test
    public void answerAndSourceRollbackTogether() {
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(prepare(1, "TRACKED"));
        SurveyInvitation invitation =
                context.getBean(SurveyInvitationMapper.class)
                        .selectOne(
                                new QueryWrapper<SurveyInvitation>().eq("batch_id", batch.getId()));
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                status -> {
                                    jdbc.update(
                                            "INSERT INTO fm_user_form_data(form_key) VALUES(?)",
                                            formKey);
                                    Long id =
                                            jdbc.queryForObject(
                                                    "SELECT id FROM fm_user_form_data WHERE"
                                                            + " form_key=?",
                                                    Long.class,
                                                    formKey);
                                    UserFormDataEntity answer = new UserFormDataEntity();
                                    answer.setId(id);
                                    answer.setFormKey(formKey);
                                    answer.setInvitationToken(invitation.getToken());
                                    context.getBean(SurveyInvitationService.class)
                                            .recordAnswer(answer);
                                    throw new IllegalStateException("rollback");
                                }));
        assertEquals(
                Integer.valueOf(0),
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fm_survey_answer_source WHERE form_key=?",
                        Integer.class,
                        formKey));
        assertEquals(
                Integer.valueOf(0),
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fm_user_form_data WHERE form_key=?",
                        Integer.class,
                        formKey));
    }

    @Test
    public void tenThousandRecipientsImportAndPreviewWithoutSending() {
        SurveyBatchRequest request = prepare(10000, "TRACKED");
        Map<String, Object> preview = context.getBean(SurveyBatchService.class).preview(request);
        assertEquals(10000, preview.get("eligible"));
        assertEquals(0, preview.get("invalidVariables"));
        assertEquals(0, calls.get());
    }

    @Test
    public void receiptEventsDeduplicateAndConflictingProviderRowsRemainAmbiguous() {
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(prepare(1, "TRACKED"));
        context.getBean(SurveyDispatchWorker.class).runOnce();
        SurveyDeliveryMapper mapper = context.getBean(SurveyDeliveryMapper.class);
        SurveyDelivery delivery =
                mapper.selectOne(new QueryWrapper<SurveyDelivery>().eq("batch_id", batch.getId()));
        cn.hutool.json.JSONObject row =
                new cn.hutool.json.JSONObject()
                        .set("id", "record-" + UUID.randomUUID())
                        .set("taskid", "shared-task")
                        .set("phoneno", delivery.getPhone())
                        .set("sendstatus", "0")
                        .set("recvstatus", "0")
                        .set("recvstatustext", "DELIVRD")
                        .set(
                                "sendtime",
                                delivery.getSubmittedAt()
                                        .format(
                                                java.time.format.DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss")))
                        .set(
                                "recvtime",
                                delivery.getSubmittedAt()
                                        .minusSeconds(5)
                                        .format(
                                                java.time.format.DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss")));
        // 排除其他测试历史的同号同任务记录，验证本轮时间窗的候选。
        mapper.update(
                null,
                new UpdateWrapper<SurveyDelivery>()
                        .ne("batch_id", batch.getId())
                        .eq("phone", delivery.getPhone())
                        .set("provider_task_id", "other-task"));
        TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        SurveyReceiptService service = context.getBean(SurveyReceiptService.class);
        tx.execute(
                s -> {
                    service.ingest(batch.getAccountKey(), row);
                    return null;
                });
        tx.execute(
                s -> {
                    service.ingest(batch.getAccountKey(), row);
                    return null;
                });
        assertEquals("SUCCESS", mapper.selectById(delivery.getId()).getDeliveryStatus());
        assertEquals(
                Long.valueOf(1),
                context.getBean(SurveyReceiptMapper.class)
                        .selectCount(
                                new QueryWrapper<SurveyReceipt>()
                                        .eq("account_key", batch.getAccountKey())
                                        .eq(
                                                "fingerprint",
                                                SurveyReceiptNormalizer.fingerprint(
                                                        SurveyReceiptNormalizer.safe(row)))));
        row.set("id", "different-" + UUID.randomUUID());
        tx.execute(
                s -> {
                    service.ingest(batch.getAccountKey(), row);
                    return null;
                });
        assertEquals("UNKNOWN", mapper.selectById(delivery.getId()).getDeliveryStatus());
        assertEquals(
                Long.valueOf(1),
                context.getBean(SurveyReceiptMapper.class)
                        .selectCount(
                                new QueryWrapper<SurveyReceipt>()
                                        .eq(
                                                "record_identity",
                                                SurveyReceiptNormalizer.identity(row))
                                        .eq("match_state", "AMBIGUOUS")));
    }

    @Test
    public void remindersExcludeCompletionAgainAtExecutionTime() {
        SurveyBatchService service = context.getBean(SurveyBatchService.class);
        SurveyBatchRequest request = prepare(2, "TRACKED");
        SurveyBatch source = service.create(request);
        context.getBean(SurveyDispatchWorker.class).runOnce();
        context.getBean(JdbcTemplate.class)
                .update(
                        "UPDATE fm_survey_delivery SET submitted_at=DATE_SUB(NOW(),INTERVAL 25"
                                + " HOUR) WHERE batch_id=?",
                        source.getId());
        List<SurveyInvitation> links =
                context.getBean(SurveyInvitationMapper.class)
                        .selectList(
                                new QueryWrapper<SurveyInvitation>()
                                        .eq("batch_id", source.getId())
                                        .orderByAsc("id"));
        recordAnswer(links.get(0));
        SurveyBatchRequest reminder = new SurveyBatchRequest();
        reminder.setTemplateId(source.getTemplateId());
        reminder.setExpectedTemplateVersion(source.getTemplateVersion());
        reminder.setRequestKey(UUID.randomUUID().toString());
        service.reminderRequest(source.getId(), reminder);
        assertEquals(1, service.preview(reminder).get("eligible"));
        SurveyBatch batch = service.create(approve(reminder));
        recordAnswer(links.get(1));
        int before = calls.get();
        context.getBean(SurveyDispatchWorker.class).runOnce();
        assertEquals(before, calls.get());
        assertEquals(
                Long.valueOf(1),
                context.getBean(SurveyDeliveryMapper.class)
                        .selectCount(
                                new QueryWrapper<SurveyDelivery>()
                                        .eq("batch_id", batch.getId())
                                        .eq("submit_status", "SKIPPED")));
        Map<String, Object> report =
                context.getBean(SurveyReportService.class).report(formKey, null, null, null);
        assertEquals(2, report.get("completedRecipients"));
        assertEquals(2, report.get("invitedRecipients"));
    }

    @Test
    public void localRefusalIsCheckedAfterBatchCreation() {
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(prepare(1, "TRACKED"));
        SurveyUpstreamService upstream = context.getBean(SurveyUpstreamService.class);
        upstream.suppress("13800000000", "测试明确拒收", true);
        context.getBean(SurveyDispatchWorker.class).runOnce();
        assertEquals(0, calls.get());
        assertEquals(
                Long.valueOf(1),
                context.getBean(SurveyDeliveryMapper.class)
                        .selectCount(
                                new QueryWrapper<SurveyDelivery>()
                                        .eq("batch_id", batch.getId())
                                        .eq("submit_status", "SKIPPED")));
    }

    @Test
    public void replyDedupAndExplicitAssignmentProtectCrossFormPrivacy() {
        prepare(1, "TRACKED");
        SurveyUpstreamService service = context.getBean(SurveyUpstreamService.class);
        String account = new YunxinClient(properties).accountKey();
        cn.hutool.json.JSONObject row =
                new cn.hutool.json.JSONObject()
                        .set("id", "reply-" + UUID.randomUUID())
                        .set("cellphone", "8613800000000")
                        .set("smscode", "test")
                        .set("content", "测试回复")
                        .set("recvtime", "2026-10-26 10:00:00");
        service.ingest(account, row);
        service.ingest(account, row);
        SurveyUpstream reply =
                context.getBean(SurveyUpstreamMapper.class)
                        .selectOne(
                                new QueryWrapper<SurveyUpstream>()
                                        .eq("provider_record_id", row.getStr("id")));
        assertNotNull(reply);
        assertNull(reply.getAssignedFormKey());
        service.assign(reply.getId(), formKey);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 2L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserFormEntity owned = new UserFormEntity();
        owned.setFormKey(formKey);
        owned.setUserId(2L);
        owned.setStatus(FormStatusEnum.RELEASE);
        when(context.getBean(UserFormService.class).getByKey(formKey)).thenReturn(owned);
        assertEquals(1, service.list(formKey, 1, 100, null, null).getRecords().size());
        assertThrows(BaseException.class, () -> service.list(null, 1, 100, null, null));
        assertThrows(BaseException.class, () -> service.assign(reply.getId(), null));
        assertThrows(
                BaseException.class, () -> context.getBean(SurveyBatchService.class).getBatch(-1L));
    }

    @Test
    public void receiptPaginationRetriesTheFailedPageWithoutDuplicateEvents() {
        properties.setYunxinReceiptEnabled(true);
        properties.setYunxinQuerySuccessCode("00000000");
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(prepare(1, "TRACKED"));
        context.getBean(SurveyDispatchWorker.class).runOnce();
        SurveyDeliveryMapper mapper = context.getBean(SurveyDeliveryMapper.class);
        SurveyDelivery delivery =
                mapper.selectOne(new QueryWrapper<SurveyDelivery>().eq("batch_id", batch.getId()));
        mapper.update(
                null,
                new UpdateWrapper<SurveyDelivery>()
                        .ne("batch_id", batch.getId())
                        .eq("phone", delivery.getPhone())
                        .set("provider_task_id", "other-task"));
        cn.hutool.json.JSONObject row =
                new cn.hutool.json.JSONObject()
                        .set("id", "paging-" + UUID.randomUUID())
                        .set("taskid", "shared-task")
                        .set("phoneno", delivery.getPhone())
                        .set("sendstatus", "0")
                        .set("recvstatus", "0")
                        .set("recvstatustext", "DELIVRD")
                        .set(
                                "sendtime",
                                delivery.getSubmittedAt()
                                        .format(
                                                java.time.format.DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss")));
        cn.hutool.json.JSONArray pageOne = new cn.hutool.json.JSONArray();
        for (int i = 0; i < 100; i++) pageOne.add(row);
        AtomicInteger pageTwo = new AtomicInteger();
        responder =
                (path, body) ->
                        path.endsWith("/task/query")
                                ? body.getInt("pageNum") == 1
                                        ? new cn.hutool.json.JSONObject()
                                                .set("code", "00000000")
                                                .set("data", pageOne)
                                                .toString()
                                        : pageTwo.incrementAndGet() == 1
                                                ? "{\"code\":\"failure\"}"
                                                : "{\"code\":\"00000000\",\"data\":[]}"
                                : response;
        SurveyReceiptService service = context.getBean(SurveyReceiptService.class);
        service.enqueueBatch(batch.getId());
        service.runDue();
        SurveySyncCursor cursor =
                context.getBean(SurveySyncCursorMapper.class)
                        .selectOne(
                                new QueryWrapper<SurveySyncCursor>()
                                        .eq("kind", "RECEIPT")
                                        .eq("source_key", "shared-task"));
        assertEquals(Integer.valueOf(2), cursor.getPageNum());
        assertEquals(Integer.valueOf(1), cursor.getFailures());
        service.enqueueBatch(batch.getId());
        service.runDue();
        cursor = context.getBean(SurveySyncCursorMapper.class).selectById(cursor.getId());
        assertEquals(Integer.valueOf(1), cursor.getPageNum());
        assertEquals(Integer.valueOf(0), cursor.getFailures());
        assertNull(cursor.getWindowStart());
        assertEquals(
                Long.valueOf(1),
                context.getBean(SurveyReceiptMapper.class)
                        .selectCount(
                                new QueryWrapper<SurveyReceipt>()
                                        .eq("provider_record_id", row.getStr("id"))));
    }

    @Test
    public void verifiedGatewayStopsWhenAnOutOfRangePageRepeats() {
        properties.setYunxinReceiptEnabled(true);
        properties.setYunxinReceiptProfile("JX_20261026");
        properties.setYunxinQueryStartField("beginTime");
        properties.setYunxinQuerySuccessCode("200");
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(prepare(1, "TRACKED"));
        context.getBean(SurveyDispatchWorker.class).runOnce();
        SurveyDeliveryMapper mapper = context.getBean(SurveyDeliveryMapper.class);
        SurveyDelivery delivery =
                mapper.selectOne(new QueryWrapper<SurveyDelivery>().eq("batch_id", batch.getId()));
        mapper.update(
                null,
                new UpdateWrapper<SurveyDelivery>()
                        .ne("batch_id", batch.getId())
                        .eq("phone", delivery.getPhone())
                        .set("provider_task_id", "other-task"));
        cn.hutool.json.JSONObject row =
                new cn.hutool.json.JSONObject()
                        .set("id", "clamp-" + UUID.randomUUID())
                        .set("taskid", "shared-task")
                        .set("phoneno", delivery.getPhone())
                        .set("sendstatuscode", "0")
                        .set("recvstatuscode", "DELIVRD")
                        .set("splitSize", 5)
                        .set(
                                "sendtime",
                                delivery.getSubmittedAt()
                                        .format(
                                                java.time.format.DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss")));
        cn.hutool.json.JSONArray fullPage = new cn.hutool.json.JSONArray();
        for (int i = 0; i < 100; i++) fullPage.add(row);
        AtomicInteger queries = new AtomicInteger();
        responder =
                (path, body) -> {
                    queries.incrementAndGet();
                    return new cn.hutool.json.JSONObject()
                            .set("code", 200)
                            .set("data", fullPage)
                            .toString();
                };
        SurveyReceiptService service = context.getBean(SurveyReceiptService.class);
        service.enqueueBatch(batch.getId());
        service.runDue();
        assertEquals(2, queries.get());
        assertEquals("SUCCESS", mapper.selectById(delivery.getId()).getDeliveryStatus());
        assertEquals(Integer.valueOf(5), mapper.selectById(delivery.getId()).getSplitSize());
        Map<String, Object> report =
                context.getBean(SurveyReportService.class)
                        .report(formKey, batch.getId(), null, null);
        assertEquals(5L, report.get("actualSegments"));
    }

    @Test
    public void concurrentDuplicateRequestsReturnOneBatch() throws Exception {
        SurveyBatchRequest request = prepare(1, "TRACKED");
        SurveyBatchService service = context.getBean(SurveyBatchService.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Callable<Long> task =
                () -> {
                    MockHttpServletRequest http = new MockHttpServletRequest();
                    http.setAttribute("userId", 1L);
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
                    try {
                        gate.await();
                        return service.create(
                                        JSONUtil.toBean(
                                                JSONUtil.toJsonStr(request),
                                                SurveyBatchRequest.class))
                                .getId();
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                };
        Future<Long> first = pool.submit(task), second = pool.submit(task);
        gate.countDown();
        try {
            assertEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                Long.valueOf(1),
                context.getBean(SurveyBatchMapper.class)
                        .selectCount(new QueryWrapper<SurveyBatch>().eq("form_key", formKey)));
    }

    @Test
    public void unknownTemplateApplicationCannotBeResetIntoAnAutomaticRetry() {
        SurveyBatchRequest prepared = prepare(1, "TRACKED");
        SurveySmsTemplateMapper mapper = context.getBean(SurveySmsTemplateMapper.class);
        mapper.update(
                null,
                new UpdateWrapper<SurveySmsTemplate>()
                        .eq("id", prepared.getTemplateId())
                        .set("provider_template_id", null)
                        .set("application_state", "SUBMISSION_UNKNOWN"));
        SurveyTemplateService service = context.getBean(SurveyTemplateService.class);
        SurveySmsTemplate template = service.get(prepared.getTemplateId(), true);
        assertEquals("SUBMISSION_UNKNOWN", service.save(template).getApplicationState());
        assertThrows(BaseException.class, () -> service.apply(template.getId()));
        assertEquals(0, calls.get());
    }

    @Test
    public void otherUsersCannotReadOrExportSomeoneElsesSurvey() {
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(prepare(1, "TRACKED"));
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("userId", 2L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        assertThrows(
                BaseException.class,
                () -> context.getBean(SurveyBatchService.class).getBatch(batch.getId()));
        assertThrows(
                BaseException.class,
                () ->
                        context.getBean(SurveyRecipientService.class)
                                .page(formKey, 1, 20, null, null, null, null));
        assertThrows(
                BaseException.class,
                () -> context.getBean(SurveySettingsService.class).get(formKey));
        assertThrows(
                BaseException.class,
                () ->
                        context.getBean(SurveyReportService.class)
                                .exportIncomplete(
                                        formKey,
                                        null,
                                        null,
                                        null,
                                        new org.springframework.mock.web
                                                .MockHttpServletResponse()));
        assertThrows(
                BaseException.class,
                () ->
                        context.getBean(SurveyTemplateService.class)
                                .get(batch.getTemplateId(), false));
    }

    @Test
    public void exportsAreValidWorkbooksAndEscapeSpreadsheetFormulas() throws Exception {
        SurveyBatchRequest request = prepare(1, "ANONYMOUS");
        SurveyRecipientMapper mapper = context.getBean(SurveyRecipientMapper.class);
        mapper.update(
                null,
                new UpdateWrapper<SurveyRecipient>()
                        .eq("form_key", formKey)
                        .set("name", "=HYPERLINK(\"example\")"));
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        context.getBean(SurveyRecipientService.class).export(formKey, response, false);
        try (org.apache.poi.ss.usermodel.Workbook workbook =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals("手机号", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.STRING,
                    workbook.getSheetAt(0).getRow(1).getCell(1).getCellType());
            assertTrue(
                    workbook.getSheetAt(0)
                            .getRow(1)
                            .getCell(1)
                            .getStringCellValue()
                            .startsWith("'="));
        }
        SurveyBatch batch = context.getBean(SurveyBatchService.class).create(approve(request));
        response = new org.springframework.mock.web.MockHttpServletResponse();
        context.getBean(SurveyReportService.class)
                .exportDeliveries(batch.getId(), null, null, null, null, null, response);
        assertTrue(response.getContentAsByteArray().length > 100);
        assertThrows(
                BaseException.class,
                () ->
                        context.getBean(SurveyReportService.class)
                                .exportIncomplete(
                                        formKey,
                                        null,
                                        null,
                                        null,
                                        new org.springframework.mock.web
                                                .MockHttpServletResponse()));
    }

    private void recordAnswer(SurveyInvitation invitation) {
        TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.execute(
                status -> {
                    JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                    jdbc.update(
                            "INSERT INTO fm_user_form_data(form_key) VALUES(?)",
                            invitation.getFormKey());
                    Long id =
                            jdbc.queryForObject(
                                    "SELECT MAX(id) FROM fm_user_form_data WHERE form_key=?",
                                    Long.class,
                                    invitation.getFormKey());
                    UserFormDataEntity answer = new UserFormDataEntity();
                    answer.setId(id);
                    answer.setFormKey(invitation.getFormKey());
                    answer.setInvitationToken(invitation.getToken());
                    context.getBean(SurveyInvitationService.class).recordAnswer(answer);
                    return null;
                });
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan("com.tduck.cloud.form.survey.mapper")
    @ComponentScan(
            basePackageClasses = SurveyGateway.class,
            excludeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = {SurveyGateway.class, SurveySchedulingConfiguration.class}))
    public static class TestConfiguration {
        @Bean
        public DataSource dataSource() {
            return new DriverManagerDataSource(
                    System.getenv("SURVEY_TEST_JDBC_URL"),
                    System.getenv("SURVEY_TEST_DB_USER"),
                    System.getenv("SURVEY_TEST_DB_PASSWORD"));
        }

        @Bean
        public JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        public PlatformTransactionManager tx(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            GlobalConfig global = new GlobalConfig();
            GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
            db.setIdType(IdType.AUTO);
            global.setDbConfig(db);
            global.setMetaObjectHandler(new AutoFillMetaInfoHandler());
            factory.setGlobalConfig(global);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(
                    new PaginationInnerInterceptor(
                            com.baomidou.mybatisplus.annotation.DbType.MYSQL));
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public UserFormService forms() {
            UserFormService forms = mock(UserFormService.class);
            when(forms.getByKey(anyString()))
                    .thenAnswer(
                            call -> {
                                UserFormEntity form = new UserFormEntity();
                                form.setFormKey(call.getArgument(0));
                                form.setName("测试问卷");
                                form.setUserId(1L);
                                form.setStatus(FormStatusEnum.RELEASE);
                                form.setDeleted(false);
                                form.setFolder(false);
                                return form;
                            });
            return forms;
        }

        @Bean
        public SurveyGateway gateway() {
            SurveyGateway gateway = mock(SurveyGateway.class);
            when(gateway.properties()).thenAnswer(call -> properties);
            when(gateway.client()).thenAnswer(call -> new YunxinClient(properties));
            when(gateway.publicBaseUrl()).thenReturn("https://survey.example.test");
            when(gateway.capabilities())
                    .thenAnswer(
                            call -> {
                                Map<String, Object> map = new HashMap<>();
                                map.put("enabled", true);
                                return map;
                            });
            return gateway;
        }
    }
}
