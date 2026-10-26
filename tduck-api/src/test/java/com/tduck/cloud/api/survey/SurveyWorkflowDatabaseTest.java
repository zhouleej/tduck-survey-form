package com.tduck.cloud.api.survey;

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
import org.junit.*;
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
import javax.sql.DataSource;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

/** 显式提供 SURVEY_TEST_JDBC_URL 才执行；只允许名称以 tduck_survey_test_ 开头的隔离MySQL库。 */
public class SurveyWorkflowDatabaseTest {
    private static AnnotationConfigApplicationContext context;
    private static HttpServer server;
    private static SmsPlatformProperties properties;
    private static final AtomicInteger calls=new AtomicInteger();
    private static volatile String response="{\"code\":\"0000000\",\"taskid\":\"shared-task\"}";
    private static final List<Integer> requestSizes=new CopyOnWriteArrayList<>();
    private String formKey;

    @BeforeClass public static void start() throws Exception {
        String url=System.getenv("SURVEY_TEST_JDBC_URL"); Assume.assumeTrue("需提供隔离MySQL测试库",url!=null);
        assertTrue("禁止对业务库执行集成测试",url.matches("jdbc:mysql://[^/]+/tduck_survey_test_[A-Za-z0-9_]+(?:\\?.*)?"));
        properties=new SmsPlatformProperties();properties.setType(SmsTypeEnum.YUNXIN);properties.setSurveyEnabled(true);
        properties.setYunxinUserId("000000000001");properties.setYunxinAccessNumber("10650001");properties.setYunxinExtendCode("000001");properties.setYunxinUsername("test");
        properties.setSurveyPublicBaseUrl("https://survey.example.test");properties.setSurveySendStartHour(0);properties.setSurveySendEndHour(24);properties.setSurveySendIntervalSeconds(1);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{calls.incrementAndGet();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=exchange.getRequestBody().read(buf))!=-1)out.write(buf,0,n);
            cn.hutool.json.JSONObject body=JSONUtil.parseObj(new String(out.toByteArray(),StandardCharsets.UTF_8));
            requestSizes.add(body.getJSONArray("varData")==null?0:body.getJSONArray("varData").size());
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        properties.setYunxinBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        context=new AnnotationConfigApplicationContext(TestConfiguration.class);
        DataSource ds=context.getBean(DataSource.class);
        Path root=Paths.get("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("doc/migrations")))root=root.getParent();assertNotNull(root);
        try(Connection c=ds.getConnection()){
            List<Path> scripts=new ArrayList<>();try(java.util.stream.Stream<Path> paths=Files.list(root.resolve("doc/migrations"))){paths.filter(p->p.toString().endsWith(".sql")).sorted().forEach(scripts::add);}
            for(Path p:scripts)ScriptUtils.executeSqlScript(c,new org.springframework.core.io.support.EncodedResource(new FileSystemResource(p.toFile()),"UTF-8"));
        }
        context.getBean(JdbcTemplate.class).execute("CREATE TABLE IF NOT EXISTS fm_user_form_data(id BIGINT AUTO_INCREMENT PRIMARY KEY,form_key VARCHAR(64),create_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
    }
    @AfterClass public static void stop(){if(context!=null)context.close();if(server!=null)server.stop(0);}
    @Before public void before(){formKey="test"+UUID.randomUUID().toString().replace("-","");MockHttpServletRequest request=new MockHttpServletRequest();request.setAttribute("userId",1L);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));calls.set(0);requestSizes.clear();response="{\"code\":\"0000000\",\"taskid\":\"shared-task\"}";}
    @After public void after(){if(context!=null)context.getBean(JdbcTemplate.class).update("UPDATE fm_survey_batch SET status='CANCELED' WHERE form_key=? AND status IN ('PENDING','RUNNING','PAUSED')",formKey);RequestContextHolder.resetRequestAttributes();}

    private SurveyBatchRequest prepare(int count,String mode){
        SurveySettingsService settings=context.getBean(SurveySettingsService.class);SurveySetting setting=settings.get(formKey);setting.setMode(mode);settings.save(setting);
        List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<count;i++){Map<String,Object> row=new HashMap<>();row.put("phone",String.valueOf(13800000000L+i));row.put("name","测试"+i);rows.add(row);}
        context.getBean(SurveyRecipientService.class).confirm(formKey,rows,false);
        SurveySmsTemplate template=new SurveySmsTemplate();template.setName("测试邀请");template.setContent("【测试】https://survey.example.test/i/#params_1#");template.setVariable(true);template.setProviderTemplateId("test-template");template.setMappingJson("{\"p1\":{\"source\":\"INVITE_TOKEN\"}}");
        template=context.getBean(SurveyTemplateService.class).save(template);
        context.getBean(SurveySmsTemplateMapper.class).update(null,new UpdateWrapper<SurveySmsTemplate>().eq("id",template.getId()).set("audit_json","{\"rx_statu\":\"通过\",\"cmcc_statu\":\"正常可用\",\"cucc_statu\":\"正常可用\",\"ctcc_statu\":\"正常可用\"}"));
        SurveyBatchRequest request=new SurveyBatchRequest();request.setFormKey(formKey);request.setTemplateId(template.getId());request.setName("测试批次");request.setRequestKey(UUID.randomUUID().toString());request.setExpectedTemplateVersion(template.getVersion());return request;
    }

    @Test public void idempotencyModeLockAndRecipientPrivacy(){
        SurveyBatchService service=context.getBean(SurveyBatchService.class);SurveyBatchRequest request=prepare(3,"ANONYMOUS");
        SurveyBatch batch=service.create(request);assertEquals(batch.getId(),service.create(request).getId());request.setName("不同内容");assertThrows(BaseException.class,()->service.create(request));
        SurveySetting setting=context.getBean(SurveySettingsService.class).get(formKey);setting.setMode("TRACKED");assertThrows(BaseException.class,()->context.getBean(SurveySettingsService.class).save(setting));
        List<SurveyInvitation> invitations=context.getBean(SurveyInvitationMapper.class).selectList(new QueryWrapper<SurveyInvitation>().eq("batch_id",batch.getId()));assertEquals(1,invitations.size());assertNull(invitations.get(0).getRecipientId());
        Map<String,Object> publicContext=context.getBean(SurveyInvitationService.class).publicContext(invitations.get(0).getToken());assertEquals(new HashSet<>(Arrays.asList("formKey","mode","notice")),publicContext.keySet());
    }

    @Test public void workerSplitsAt100AndNeverResendsAcceptedRows(){
        SurveyBatch batch=context.getBean(SurveyBatchService.class).create(prepare(101,"TRACKED"));
        context.getBean(SurveyDispatchWorker.class).runOnce();assertEquals(Arrays.asList(100,1),requestSizes);
        assertEquals(Long.valueOf(101),context.getBean(SurveyDeliveryMapper.class).selectCount(new QueryWrapper<SurveyDelivery>().eq("batch_id",batch.getId()).eq("submit_status","ACCEPTED")));
        int before=calls.get();context.getBean(SurveyDispatchWorker.class).runOnce();assertEquals(before,calls.get());
    }

    @Test public void abandonedSubmissionBecomesUnknownAndIsNeverReplayed(){
        SurveyBatch batch=context.getBean(SurveyBatchService.class).create(prepare(1,"TRACKED"));
        SurveySendAttempt attempt=new SurveySendAttempt();attempt.setFormKey(formKey);attempt.setBatchId(batch.getId());attempt.setAccountKey(batch.getAccountKey());attempt.setState("SUBMITTING");attempt.setRequestDigest("test");attempt.setStartedAt(SurveyBatchService.now().minusMinutes(4));context.getBean(SurveySendAttemptMapper.class).insert(attempt);
        context.getBean(SurveyDeliveryMapper.class).update(null,new UpdateWrapper<SurveyDelivery>().eq("batch_id",batch.getId()).set("attempt_id",attempt.getId()).set("submit_status","SUBMITTING").set("submitted_at",attempt.getStartedAt()));
        context.getBean(SurveyDispatchWorker.class).runOnce();assertEquals(0,calls.get());
        assertEquals("UNKNOWN",context.getBean(SurveySendAttemptMapper.class).selectById(attempt.getId()).getState());
        context.getBean(SurveyDispatchWorker.class).runOnce();assertEquals(0,calls.get());
    }

    @Test public void databaseLeaseAllowsOnlyOneConcurrentOwner() throws Exception {
        SurveyDispatchLeaseMapper leases=context.getBean(SurveyDispatchLeaseMapper.class);String key="test:"+UUID.randomUUID();leases.ensure(key);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
        Callable<Integer> contender=()->{gate.await();return leases.claim(key,UUID.randomUUID().toString(),SurveyBatchService.now(),SurveyBatchService.now().plusMinutes(3));};
        Future<Integer> a=pool.submit(contender),b=pool.submit(contender);gate.countDown();assertEquals(1,a.get()+b.get());pool.shutdown();
    }

    @Test public void answerAndSourceRollbackTogether(){
        SurveyBatch batch=context.getBean(SurveyBatchService.class).create(prepare(1,"TRACKED"));
        SurveyInvitation invitation=context.getBean(SurveyInvitationMapper.class).selectOne(new QueryWrapper<SurveyInvitation>().eq("batch_id",batch.getId()));
        JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);TransactionTemplate tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,()->tx.execute(status->{jdbc.update("INSERT INTO fm_user_form_data(form_key) VALUES(?)",formKey);Long id=jdbc.queryForObject("SELECT id FROM fm_user_form_data WHERE form_key=?",Long.class,formKey);UserFormDataEntity answer=new UserFormDataEntity();answer.setId(id);answer.setFormKey(formKey);answer.setInvitationToken(invitation.getToken());context.getBean(SurveyInvitationService.class).recordAnswer(answer);throw new IllegalStateException("rollback");}));
        assertEquals(Integer.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM fm_survey_answer_source WHERE form_key=?",Integer.class,formKey));
        assertEquals(Integer.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM fm_user_form_data WHERE form_key=?",Integer.class,formKey));
    }

    @Test public void tenThousandRecipientsImportAndPreviewWithoutSending(){
        SurveyBatchRequest request=prepare(10000,"TRACKED");Map<String,Object> preview=context.getBean(SurveyBatchService.class).preview(request);assertEquals(10000,preview.get("eligible"));assertEquals(0,preview.get("invalidVariables"));assertEquals(0,calls.get());
    }

    @Configuration @EnableTransactionManagement
    @MapperScan("com.tduck.cloud.form.survey.mapper")
    @ComponentScan(basePackageClasses=SurveyGateway.class,excludeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes={SurveyGateway.class,SurveySchedulingConfiguration.class}))
    public static class TestConfiguration {
        @Bean public DataSource dataSource(){return new DriverManagerDataSource(System.getenv("SURVEY_TEST_JDBC_URL"),System.getenv("SURVEY_TEST_DB_USER"),System.getenv("SURVEY_TEST_DB_PASSWORD"));}
        @Bean public JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean public PlatformTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean public SqlSessionFactory sqlSessionFactory(DataSource ds)throws Exception{MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);MybatisConfiguration config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);GlobalConfig global=new GlobalConfig();GlobalConfig.DbConfig db=new GlobalConfig.DbConfig();db.setIdType(IdType.AUTO);global.setDbConfig(db);global.setMetaObjectHandler(new AutoFillMetaInfoHandler());factory.setGlobalConfig(global);MybatisPlusInterceptor interceptor=new MybatisPlusInterceptor();interceptor.addInnerInterceptor(new PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));factory.setPlugins(interceptor);return factory.getObject();}
        @Bean public UserFormService forms(){UserFormService forms=mock(UserFormService.class);when(forms.getByKey(anyString())).thenAnswer(call->{UserFormEntity form=new UserFormEntity();form.setFormKey(call.getArgument(0));form.setName("测试问卷");form.setUserId(1L);form.setStatus(FormStatusEnum.RELEASE);form.setDeleted(false);form.setFolder(false);return form;});return forms;}
        @Bean public SurveyGateway gateway(){SurveyGateway gateway=mock(SurveyGateway.class);when(gateway.properties()).thenAnswer(call->properties);when(gateway.client()).thenAnswer(call->new YunxinClient(properties));when(gateway.publicBaseUrl()).thenReturn("https://survey.example.test");when(gateway.capabilities()).thenAnswer(call->{Map<String,Object> map=new HashMap<>();map.put("enabled",true);return map;});return gateway;}
    }
}
