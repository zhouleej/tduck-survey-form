package com.tduck.cloud.form.survey;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tduck.cloud.common.util.SecurityUtils;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.tduck.cloud.form.survey.SurveyAccess.check;

@Service
@RequiredArgsConstructor
public class SurveyBatchService extends ServiceImpl<SurveyDeliveryMapper, SurveyDelivery> {
    private final SurveyBatchMapper batches;
    private final SurveyRecipientMapper recipients;
    private final SurveySettingMapper settings;
    private final SurveySettingsService settingsService;
    private final SurveyTemplateService templates;
    private final SurveyInvitationService invitations;
    private final SurveySuppressionMapper suppressions;
    private final SurveyProgressMapper progress;
    private final SurveyGateway gateway;
    private final SurveyAccess access;

    public SurveyBatch getBatch(Long id) {
        SurveyBatch batch = batches.selectById(id);
        check(batch != null, "邀请批次不存在"); access.requireForm(batch.getFormKey()); return batch;
    }

    public Page<SurveyBatch> listBatches(String formKey, int page, int size, String status) {
        access.requireForm(formKey); check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        return batches.selectPage(new Page<>(page, size), new QueryWrapper<SurveyBatch>().eq("form_key", formKey).eq(status != null && !status.isEmpty(), "status", status).orderByDesc("id"));
    }

    public Map<String,Object> deliveries(Long id, int page, int size, String keyword, String submitStatus, String sendStatus, String deliveryStatus, String participation) {
        SurveyBatch batch = getBatch(id); check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        QueryWrapper<SurveyDelivery> query = new QueryWrapper<SurveyDelivery>().eq("batch_id", id);
        if (keyword != null && !keyword.isEmpty()) query.and(q -> q.like("phone", keyword).or().like("name", keyword).or().like("company", keyword));
        if (submitStatus != null && !submitStatus.isEmpty()) query.eq("submit_status", submitStatus);
        if (sendStatus != null && !sendStatus.isEmpty()) query.eq("send_status", sendStatus);
        if (deliveryStatus != null && !deliveryStatus.isEmpty()) query.eq("delivery_status", deliveryStatus);
        Set<Long> completed = "TRACKED".equals(batch.getMode()) ? new HashSet<>(progress.completed(batch.getFormKey())) : Collections.emptySet();
        Set<Long> visited = "TRACKED".equals(batch.getMode()) ? new HashSet<>(progress.visited(batch.getFormKey())) : Collections.emptySet();
        applyParticipation(query, batch.getMode(), participation, completed, visited, "recipient_id");
        Page<SurveyDelivery> result = page(new Page<>(page,size), query.orderByAsc("id"));
        List<Map<String,Object>> rows = result.getRecords().stream().map(row -> {
            Map<String,Object> value = JSONUtil.parseObj(JSONUtil.toJsonStr(row));
            // Hutool 不遵循 Jackson 的 JsonIgnore，显式删除内部变量及邀请内容上下文。
            value.remove("variablesJson");
            value.put("participation", "TRACKED".equals(batch.getMode()) ? completed.contains(row.getRecipientId()) ? "COMPLETED" : visited.contains(row.getRecipientId()) ? "VISITED" : "NOT_VISITED" : null);
            return value;
        }).collect(Collectors.toList());
        Map<String,Object> response = new LinkedHashMap<>(); response.put("records",rows); response.put("total",result.getTotal()); return response;
    }

    public List<Map<String,Object>> answers(String formKey, Long recipientId) {
        access.requireForm(formKey);
        SurveyRecipient row = recipients.selectById(recipientId); check(row != null && formKey.equals(row.getFormKey()), "调研对象不存在");
        check("TRACKED".equals(settingsService.ensure(formKey).getMode()), "匿名调研不提供对象答卷关联");
        return progress.answers(formKey, recipientId);
    }

    public Map<String,Object> preview(SurveyBatchRequest request) {
        UserFormEntity form = access.requireReleased(request.getFormKey());
        SurveySetting setting = settingsService.get(request.getFormKey());
        SurveySmsTemplate template = templates.get(request.getTemplateId(), false);
        List<SurveyRecipient> selected = select(request, setting.getMode());
        List<SurveyRecipient> eligible = new ArrayList<>(); Map<String,Integer> excluded = new LinkedHashMap<>();
        String account = gateway.client().accountKey();
        Map<Long,String> reasons = exclusionReasons(request.getFormKey(), selected, request.getKind(), null, account);
        for (SurveyRecipient row : selected) { String reason = reasons.get(row.getId()); if (reason != null) excluded.put(reason, excluded.getOrDefault(reason,0)+1); else eligible.add(row); }
        String token = SurveyInvitationService.token(), link = gateway.publicBaseUrl() + "/i/" + token;
        List<Map<String,Object>> samples = new ArrayList<>(); int invalid = 0; boolean hasLink = true;
        for (SurveyRecipient recipient : eligible) {
            try {
                Map<String,Object> variables = SurveyTemplateRenderer.variables(template, form, recipient, link, token);
                String content = SurveyTemplateRenderer.render(template, variables);
                hasLink &= content.contains(link);
                if (samples.size() < 3) { Map<String,Object> sample = new LinkedHashMap<>(); sample.put("phone", recipient.getPhone()); sample.put("content",content); sample.put("link",link); samples.add(sample); }
            } catch (Exception e) { invalid++; }
        }
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("total", selected.size()); response.put("eligible",eligible.size()); response.put("excluded",excluded); response.put("invalidVariables",invalid);
        response.put("mode",setting.getMode()); response.put("samples",samples); response.put("hasInvitationLink",hasLink);
        response.put("templateVersion",template.getVersion()); response.put("approved",SurveyTemplateService.approved(template));
        response.put("allCarriersApproved",SurveyTemplateService.allCarriersApproved(template)); response.put("audit",JSONUtil.parseObj(template.getAuditJson()==null?"{}":template.getAuditJson()));
        response.put("enabled", gateway.capabilities().get("enabled"));
        return response;
    }

    @Transactional(rollbackFor=Exception.class)
    public SurveyBatch create(SurveyBatchRequest request) {
        access.requireReleased(request.getFormKey());
        check(request.getRequestKey() != null && request.getRequestKey().matches("[A-Za-z0-9_-]{8,64}"), "缺少有效幂等键");
        if (request.getRecipientIds() != null) request.setRecipientIds(request.getRecipientIds().stream().distinct().sorted().collect(Collectors.toList()));
        String hash = DigestUtil.sha256Hex(JSONUtil.toJsonStr(request));
        SurveyBatch prior = batches.selectOne(new QueryWrapper<SurveyBatch>().eq("form_key",request.getFormKey()).eq("request_key",request.getRequestKey()));
        if (prior != null) { check(hash.equals(prior.getRequestHash()), "同一幂等键不能提交不同内容"); return prior; }
        gateway.requireEnabled(); settingsService.ensure(request.getFormKey());
        SurveySetting setting = settings.selectOne(new QueryWrapper<SurveySetting>().eq("form_key",request.getFormKey()).last("FOR UPDATE"));
        prior = batches.selectOne(new QueryWrapper<SurveyBatch>().eq("form_key",request.getFormKey()).eq("request_key",request.getRequestKey()));
        if (prior != null) { check(hash.equals(prior.getRequestHash()), "同一幂等键不能提交不同内容"); return prior; }
        Map<String,Object> preview = preview(request);
        check(Boolean.TRUE.equals(preview.get("approved")), "模板尚未通过云信审核，请先查询审核状态");
        check(request.getExpectedTemplateVersion()!=null && request.getExpectedTemplateVersion().equals(preview.get("templateVersion")), "模板已变化，请重新预览");
        check(Boolean.TRUE.equals(preview.get("allCarriersApproved")) || request.isCoverageAccepted(), "请确认当前模板运营商覆盖范围");
        check(((Number)preview.get("invalidVariables")).intValue()==0, "部分对象的变量缺失或超长，请修正后重试");
        check(Boolean.TRUE.equals(preview.get("hasInvitationLink")) || ("ANONYMOUS".equals(setting.getMode()) && request.isUntrackedAccepted()), "模板没有完整邀请链接，无法跟踪；匿名发送需确认不提供批次回收归因");
        List<SurveyRecipient> selected = select(request,setting.getMode());
        String account = gateway.client().accountKey(); Map<Long,String> reasons = exclusionReasons(request.getFormKey(),selected,request.getKind(),null,account);
        List<SurveyRecipient> eligible = selected.stream().filter(r -> !reasons.containsKey(r.getId())).collect(Collectors.toList());
        check(!eligible.isEmpty() && eligible.size()<=10000, "没有符合条件的对象，或批次超过1万人");
        SurveySmsTemplate template = templates.get(request.getTemplateId(),false);
        check(template.getVersion().equals(request.getExpectedTemplateVersion()), "模板已变化，请重新预览");
        LocalDateTime now=now(); check(request.getScheduledAt()==null || !request.getScheduledAt().isBefore(now.minusMinutes(1)), "预约时间已过，请重新选择");
        String name=request.getName()==null||request.getName().trim().isEmpty()?"问卷邀请":request.getName().trim(); check(name.length()<=100,"批次名称过长");
        SurveyBatch batch=new SurveyBatch(); batch.setFormKey(request.getFormKey()); batch.setName(name); batch.setKind(request.getKind()); batch.setMode(setting.getMode());
        batch.setTemplateId(template.getId());batch.setTemplateVersion(template.getVersion());batch.setTemplateSnapshot(JSONUtil.toJsonStr(template));batch.setAccountKey(account);batch.setConfigVersion(gateway.client().configurationVersion());
        batch.setStatus("PENDING");batch.setScheduledAt(request.getScheduledAt()==null?now:request.getScheduledAt());batch.setOwnerId(SecurityUtils.getUserId());batch.setRequestKey(request.getRequestKey());batch.setRequestHash(hash);
        batch.setRecipientCount(eligible.size());batch.setExcludedCount(selected.size()-eligible.size());batch.setSourceBatchId(request.getSourceBatchId());batch.setCoverageAccepted(request.isCoverageAccepted()); batches.insert(batch);
        SurveyInvitation shared="ANONYMOUS".equals(setting.getMode())?invitations.create(batch.getFormKey(),batch.getId(),null,setting.getMode()):null;
        UserFormEntity form=access.existingForm(batch.getFormKey()); String base=gateway.publicBaseUrl(); List<SurveyDelivery> rows=new ArrayList<>();
        for(SurveyRecipient recipient:eligible){
            SurveyInvitation invitation=shared==null?invitations.create(batch.getFormKey(),batch.getId(),recipient.getId(),setting.getMode()):shared;
            Map<String,Object> variables=SurveyTemplateRenderer.variables(template,form,recipient,base+"/i/"+invitation.getToken(),invitation.getToken());
            SurveyDelivery row=new SurveyDelivery();row.setFormKey(batch.getFormKey());row.setBatchId(batch.getId());row.setRecipientId(recipient.getId());row.setInvitationId(invitation.getId());
            row.setPhone(recipient.getPhone());row.setName(recipient.getName());row.setCompany(recipient.getCompany());row.setGroupName(recipient.getGroupName());row.setVariablesJson(JSONUtil.toJsonStr(variables));row.setRenderedContent(SurveyTemplateRenderer.render(template,variables));
            row.setSubmitStatus("PENDING");row.setSendStatus("PENDING");row.setDeliveryStatus("WAITING");rows.add(row);
        }
        saveBatch(rows,500); setting.setModeLocked(true);settings.updateById(setting);access.audit(batch.getFormKey(),"BATCH_CREATE",batch.getId(),"创建"+batch.getKind()+"批次，对象数"+rows.size());return batch;
    }

    public List<SurveyRecipient> select(SurveyBatchRequest request, String mode) {
        check(Arrays.asList("INVITE","REMINDER","RETRY","REINVITE").contains(request.getKind()),"邀请类型无效");
        if("REMINDER".equals(request.getKind()))check("TRACKED".equals(mode),"匿名调研不能精准筛选未填写对象，请使用再次邀请");
        QueryWrapper<SurveyRecipient> query=new QueryWrapper<SurveyRecipient>().eq("form_key",request.getFormKey());
        if(request.getRecipientIds()!=null){check(!request.getRecipientIds().isEmpty()&&request.getRecipientIds().size()<=10000,"请选择1至10000个对象");query.in("id",request.getRecipientIds());}
        if(request.getGroupName()!=null&&!request.getGroupName().isEmpty())query.eq("group_name",request.getGroupName());
        if(request.getCompany()!=null&&!request.getCompany().isEmpty())query.eq("company",request.getCompany());
        if(request.getSourceBatchId()!=null){
            SurveyBatch source=getBatch(request.getSourceBatchId());check(request.getFormKey().equals(source.getFormKey()),"来源批次与问卷不匹配");
            QueryWrapper<SurveyDelivery> previous=new QueryWrapper<SurveyDelivery>().eq("batch_id",source.getId());
            if("RETRY".equals(request.getKind()))previous.and(q->q.eq("submit_status","REJECTED").or().eq("send_status","FAILED").or().eq("delivery_status","FAILED"));
            List<Long> ids=list(previous).stream().map(SurveyDelivery::getRecipientId).collect(Collectors.toList());
            if(ids.isEmpty())return Collections.emptyList();query.in("id",ids);
        } else check(!"RETRY".equals(request.getKind()),"再次发送失败对象须指定来源批次");
        if(request.getParticipation()!=null&&!request.getParticipation().isEmpty()){
            Set<Long> completed=new HashSet<>(progress.completed(request.getFormKey())), visited=new HashSet<>(progress.visited(request.getFormKey()));
            applyParticipation(query,mode,request.getParticipation(),completed,visited, "id");
        }
        query.orderByAsc("id").last("LIMIT 10001");List<SurveyRecipient> selected=recipients.selectList(query);check(selected.size()<=10000,"单个批次最多选择1万人，请按分组拆分");return selected;
    }

    public Map<Long,String> exclusionReasons(String formKey,List<SurveyRecipient> selected,String kind,Long currentBatch,String account){
        Set<Long> completed="REMINDER".equals(kind)?new HashSet<>(progress.completed(formKey)):Collections.emptySet();
        Set<String> blocked=suppressions.selectList(new QueryWrapper<SurveySuppression>().eq("account_key",account).eq("active",true)).stream().map(SurveySuppression::getPhone).collect(Collectors.toSet());
        List<SurveyDelivery> history=list(new QueryWrapper<SurveyDelivery>().eq("form_key",formKey).ne(currentBatch!=null,"batch_id",currentBatch)
                .and(q->q.ge("submitted_at",now().minusHours(24)).or().in("submit_status",Arrays.asList("PENDING","SUBMITTING","UNKNOWN"))));
        Set<String> active=new HashSet<>(), recent=new HashSet<>();
        for(SurveyDelivery row:history){if(Arrays.asList("PENDING","SUBMITTING","UNKNOWN").contains(row.getSubmitStatus()))active.add(row.getPhone());if(row.getSubmittedAt()!=null&&row.getSubmittedAt().isAfter(now().minusHours(24)))recent.add(row.getPhone());}
        Map<Long,String> reasons=new HashMap<>();
        for(SurveyRecipient row:selected){String reason=null;
            if(Boolean.TRUE.equals(row.getDisabled()))reason="联系人已停用";else if(blocked.contains(row.getPhone()))reason="已设置拒收";else if(completed.contains(row.getId()))reason="已完成";else if(active.contains(row.getPhone()))reason="存在待发送或受理未知记录";else if(recent.contains(row.getPhone()))reason="距上次发送不足24小时";
            if(reason!=null)reasons.put(row.getId(),reason);
        }return reasons;
    }

    @Transactional(rollbackFor=Exception.class)
    public SurveyBatch action(Long id,String action){
        SurveyBatch owned=getBatch(id);SurveyBatch batch=batches.selectOne(new QueryWrapper<SurveyBatch>().eq("id",id).last("FOR UPDATE"));
        check(Arrays.asList("PENDING","RUNNING","PAUSED").contains(batch.getStatus()),"批次已结束，无法执行此操作");
        if("cancel".equals(action)){
            batch.setStatus("CANCELED");batch.setReason("操作人取消待发送部分，已提交短信无法撤回");
            update(new UpdateWrapper<SurveyDelivery>().eq("batch_id",id).eq("submit_status","PENDING").set("submit_status","CANCELED").set("reason","操作人取消"));
        }else if("pause".equals(action)){batch.setStatus("PAUSED");batch.setReason("操作人暂停待发送队列");}
        else if("resume".equals(action)){
            access.requireReleased(batch.getFormKey());gateway.requireEnabled();
            check("PAUSED".equals(batch.getStatus()),"只有暂停的批次可以继续");
            SurveySmsTemplate template=templates.get(batch.getTemplateId(),false);check(batch.getTemplateVersion().equals(template.getVersion())&&SurveyTemplateService.approved(template),"模板已变化或未通过审核，请取消待发送部分并重新预览创建");
            check(batch.getAccountKey().equals(gateway.client().accountKey()),"云信账户已改变，不能继续旧批次");
            batch.setConfigVersion(gateway.client().configurationVersion());batch.setStatus("PENDING");batch.setReason("");
        }else check(false,"批次操作无效");
        batches.updateById(batch);access.audit(owned.getFormKey(),"BATCH_"+action.toUpperCase(Locale.ROOT),id,"更新本地待发送队列");return batch;
    }

    private static void applyParticipation(QueryWrapper<?> query,String mode,String state,Set<Long> completed,Set<Long> visited,String column){
        if(state==null||state.isEmpty())return;check("TRACKED".equals(mode),"匿名调研不提供逐人填写筛选");
        Set<Long> ids=new HashSet<>();
        if("COMPLETED".equals(state)){ids.addAll(completed);if(ids.isEmpty())query.apply("1=0");else query.in(column,ids);}
        else if("NOT_COMPLETED".equals(state)){if(!completed.isEmpty())query.notIn(column,completed);}
        else if("VISITED".equals(state)){ids.addAll(visited);ids.removeAll(completed);if(ids.isEmpty())query.apply("1=0");else query.in(column,ids);}
        else if("NOT_VISITED".equals(state)){ids.addAll(completed);ids.addAll(visited);if(!ids.isEmpty())query.notIn(column,ids);}
        else check(false,"填写状态筛选无效");
    }
    public static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
}
