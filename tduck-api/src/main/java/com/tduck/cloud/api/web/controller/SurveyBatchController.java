package com.tduck.cloud.api.web.controller;
import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.survey.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/form/sms/batches")
@RequiredArgsConstructor
public class SurveyBatchController {
    private final SurveyBatchService batches;
    @GetMapping public Result<?> list(@RequestParam String formKey,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,String status){return Result.success(batches.listBatches(formKey,page,size,status));}
    @PostMapping("/preview") public Result<?> preview(@RequestBody SurveyBatchRequest request){return Result.success(batches.preview(request));}
    @PostMapping public Result<?> create(@RequestBody SurveyBatchRequest request){return Result.success(batches.create(request));}
    @GetMapping("/{id}") public Result<?> detail(@PathVariable Long id){return Result.success(batches.getBatch(id));}
    @GetMapping("/{id}/deliveries") public Result<?> deliveries(@PathVariable Long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,String keyword,String submitStatus,String sendStatus,String deliveryStatus,String participation){return Result.success(batches.deliveries(id,page,size,keyword,submitStatus,sendStatus,deliveryStatus,participation));}
    @PostMapping("/{id}/actions/{action}") public Result<?> action(@PathVariable Long id,@PathVariable String action){return Result.success(batches.action(id,action));}
    @GetMapping("/answers") public Result<?> answers(@RequestParam String formKey,@RequestParam Long recipientId){return Result.success(batches.answers(formKey,recipientId));}
}
