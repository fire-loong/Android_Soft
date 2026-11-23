package com.actionloop.api.job;

/** 各异步类型的业务处理器；实现必须幂等，并在 Provider 调用前后校验授权。 */
public interface JobHandler {

    String type();

    void execute(JobExecution execution);
}
