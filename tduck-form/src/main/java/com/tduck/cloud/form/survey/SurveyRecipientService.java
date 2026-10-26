package com.tduck.cloud.form.survey;

import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tduck.cloud.form.survey.entity.SurveyRecipient;
import com.tduck.cloud.form.survey.mapper.SurveyRecipientMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

@Service
@RequiredArgsConstructor
public class SurveyRecipientService extends ServiceImpl<SurveyRecipientMapper, SurveyRecipient> {
    private final SurveyAccess access;
    private static final List<String> ATTRIBUTES = Arrays.asList("name", "company", "groupName", "remark");

    public Page<SurveyRecipient> page(String formKey, int page, int size, String keyword, String groupName, String company, Boolean disabled) {
        access.requireForm(formKey);
        check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        return page(new Page<>(page, size), filter(formKey, keyword, groupName, company, disabled).orderByDesc("id"));
    }

    public QueryWrapper<SurveyRecipient> filter(String formKey, String keyword, String groupName, String company, Boolean disabled) {
        QueryWrapper<SurveyRecipient> query = new QueryWrapper<SurveyRecipient>().eq("form_key", formKey);
        if (keyword != null && !keyword.trim().isEmpty()) query.and(q -> q.like("phone", keyword).or().like("name", keyword).or().like("company", keyword));
        if (groupName != null && !groupName.isEmpty()) query.eq("group_name", groupName);
        if (company != null && !company.isEmpty()) query.eq("company", company);
        if (disabled != null) query.eq("disabled", disabled);
        return query;
    }

    @Transactional(rollbackFor = Exception.class)
    public SurveyRecipient saveRecipient(SurveyRecipient request) {
        access.requireForm(request.getFormKey());
        String phone = normalizePhone(request.getPhone());
        check(validPhone(phone), "请输入正确的11位手机号码");
        validateAttributes(toMap(request));
        SurveyRecipient current;
        if (request.getId() != null) {
            current = getById(request.getId());
            check(current != null && request.getFormKey().equals(current.getFormKey()), "联系人不存在");
            check(phone.equals(current.getPhone()), "号码不可修改，请新增联系人并停用旧号码");
        } else {
            check(count(new QueryWrapper<SurveyRecipient>().eq("form_key", request.getFormKey()).eq("phone", phone)) == 0, "该号码已在名单中");
            current = new SurveyRecipient();
            current.setFormKey(request.getFormKey());
            current.setPhone(phone);
            current.setDisabled(false);
        }
        current.setName(request.getName()); current.setCompany(request.getCompany());
        current.setGroupName(request.getGroupName()); current.setRemark(request.getRemark());
        if (request.getDisabled() != null) current.setDisabled(request.getDisabled());
        saveOrUpdate(current);
        access.audit(current.getFormKey(), "RECIPIENT_SAVE", current.getId(), "维护调研对象");
        return current;
    }

    public List<Map<String, Object>> readExcel(MultipartFile file) throws IOException {
        check(file != null && !file.isEmpty() && file.getSize() <= 2 * 1024 * 1024, "请选择不超过2MB的Excel文件");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        check(name.endsWith(".xls") || name.endsWith(".xlsx"), "仅支持xls或xlsx文件");
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            check(sheet.getLastRowNum() <= 10000, "每次最多导入1万行，请拆分文件");
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(0);
            check(header != null, "Excel缺少表头");
            Map<Integer, String> columns = new LinkedHashMap<>();
            Map<String, String> aliases = new HashMap<>();
            aliases.put("手机号", "phone"); aliases.put("手机号码", "phone");
            aliases.put("姓名", "name"); aliases.put("单位", "company"); aliases.put("分组", "groupName"); aliases.put("备注", "remark");
            for (Cell cell : header) {
                String label = formatter.formatCellValue(cell).trim();
                String key = aliases.getOrDefault(label, label);
                if ("phone".equals(key) || ATTRIBUTES.contains(key)) columns.put(cell.getColumnIndex(), key);
            }
            check(columns.containsValue("phone"), "Excel必须包含“手机号”列");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i); if (row == null) continue;
                Map<String, Object> value = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> col : columns.entrySet()) {
                    Cell cell = row.getCell(col.getKey());
                    check(cell == null || cell.getCellType() != CellType.FORMULA, "导入文件不能包含公式，请粘贴为纯文本");
                    value.put(col.getValue(), cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                if (value.values().stream().allMatch(v -> v.toString().isEmpty())) continue;
                rows.add(value);
            }
            return rows;
        }
    }

    public Map<String, Object> preview(String formKey, List<Map<String, Object>> rows) {
        access.requireForm(formKey);
        check(rows != null && !rows.isEmpty() && rows.size() <= 10000, "每次请提供1至10000条名单");
        Map<String, SurveyRecipient> existing = list(new QueryWrapper<SurveyRecipient>().eq("form_key", formKey))
                .stream().collect(Collectors.toMap(SurveyRecipient::getPhone, v -> v));
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> input : rows) {
            check(input != null, "名单记录不能为空");
            String phone = normalizePhone(Objects.toString(input.get("phone"), ""));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phone", phone);
            for (String attr : ATTRIBUTES) if (input.containsKey(attr)) row.put(attr, Objects.toString(input.get(attr), ""));
            String status;
            try { validateAttributes(row); status = !validPhone(phone) ? "INVALID" : !seen.add(phone) ? "DUPLICATE" : existing.containsKey(phone) ? "EXISTING" : "NEW"; }
            catch (Exception e) { status = "INVALID"; }
            row.put("status", status);
            counts.put(status, counts.getOrDefault(status, 0) + 1);
            result.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", result); response.put("counts", counts); response.put("total", result.size());
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> confirm(String formKey, List<Map<String, Object>> rows, boolean updateExisting) {
        List<Map<String, Object>> checked = (List<Map<String, Object>>) preview(formKey, rows).get("rows");
        Map<String, SurveyRecipient> existing = list(new QueryWrapper<SurveyRecipient>().eq("form_key", formKey))
                .stream().collect(Collectors.toMap(SurveyRecipient::getPhone, v -> v));
        List<SurveyRecipient> added = new ArrayList<>(), updated = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> row : checked) {
            String status = row.get("status").toString();
            if (!"NEW".equals(status) && !("EXISTING".equals(status) && updateExisting)) { skipped++; continue; }
            SurveyRecipient recipient = existing.get(row.get("phone").toString());
            boolean fresh = recipient == null;
            if (fresh) { recipient = new SurveyRecipient(); recipient.setFormKey(formKey); recipient.setPhone(row.get("phone").toString()); recipient.setDisabled(false); }
            if (row.containsKey("name")) recipient.setName(row.get("name").toString());
            if (row.containsKey("company")) recipient.setCompany(row.get("company").toString());
            if (row.containsKey("groupName")) recipient.setGroupName(row.get("groupName").toString());
            if (row.containsKey("remark")) recipient.setRemark(row.get("remark").toString());
            (fresh ? added : updated).add(recipient);
        }
        if (!added.isEmpty()) saveBatch(added, 500);
        if (!updated.isEmpty()) updateBatchById(updated, 500);
        access.audit(formKey, "RECIPIENT_IMPORT", null, "新增" + added.size() + "，更新" + updated.size() + "，跳过" + skipped);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added.size()); result.put("updated", updated.size()); result.put("skipped", skipped);
        return result;
    }

    public void export(String formKey, HttpServletResponse response, boolean template) throws IOException {
        access.requireForm(formKey);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!template) {
            for (SurveyRecipient r : list(new QueryWrapper<SurveyRecipient>().eq("form_key", formKey).orderByAsc("id"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("手机号", r.getPhone()); row.put("姓名", safeExcel(r.getName())); row.put("单位", safeExcel(r.getCompany()));
                row.put("分组", safeExcel(r.getGroupName())); row.put("备注", safeExcel(r.getRemark())); row.put("已停用", Boolean.TRUE.equals(r.getDisabled()) ? "是" : "否"); rows.add(row);
            }
        }
        access.audit(formKey, template ? "RECIPIENT_TEMPLATE" : "RECIPIENT_EXPORT", null, "导出条数" + rows.size());
        writeExcel(response, rows, Arrays.asList("手机号", "姓名", "单位", "分组", "备注"));
    }

    public static void writeExcel(HttpServletResponse response, List<Map<String, Object>> rows, List<String> headers) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=survey.xlsx");
        try (ExcelWriter writer = ExcelUtil.getWriter(true)) {
            if (rows.isEmpty()) writer.writeHeadRow(headers); else writer.write(rows, true);
            writer.flush(response.getOutputStream(), false);
        }
    }

    public static String safeExcel(String text) {
        if (text == null) return "";
        return text.matches("^[=+@\\-\\t\\r].*") ? "'" + text : text;
    }

    public static String normalizePhone(String phone) {
        String result = phone == null ? "" : phone.replaceAll("[\\s-]", "");
        if (result.startsWith("+86")) result = result.substring(3);
        else if (result.startsWith("86") && result.length() == 13) result = result.substring(2);
        return result;
    }
    public static boolean validPhone(String phone) { return phone != null && phone.matches("1[3-9][0-9]{9}"); }
    private static void validateAttributes(Map<String, Object> row) {
        for (String key : ATTRIBUTES) {
            int max = "remark".equals(key) ? 1000 : "company".equals(key) ? 200 : 100;
            check(Objects.toString(row.get(key), "").length() <= max, "联系人属性超过长度限制");
        }
    }
    private Map<String, Object> toMap(SurveyRecipient r) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", r.getName()); values.put("company", r.getCompany()); values.put("groupName", r.getGroupName()); values.put("remark", r.getRemark()); return values;
    }
}
