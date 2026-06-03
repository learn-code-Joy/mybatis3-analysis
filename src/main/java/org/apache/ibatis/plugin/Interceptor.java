/**
 *    Copyright 2009-2025 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.plugin;

import java.util.Properties;

/**
 * MyBatis允许我们自定义 Interceptor 拦截 SQL 语句执行过程中的某些关键逻辑，允许拦截的方法有：
 * Executor 类中的 update()、query()、flushStatements()、commit()、rollback()、getTransaction()、close()、isClosed()方法，
 * ParameterHandler 中的 setParameters()、getParameterObject() 方法，
 * ResultSetHandler中的 handleOutputParameters()、handleResultSets()方法，
 * 以及StatementHandler 中的parameterize()、prepare()、batch()、update()、query()方法。
 * @author Clinton Begin
 */
public interface Interceptor {

  // 插件实现类中需要实现的拦截逻辑
  Object intercept(Invocation invocation) throws Throwable;

  // 在该方法中会决定是否触发intercept()方法
  default Object plugin(Object target) {
    // 依赖Plugin工具类创建代理对象
    return Plugin.wrap(target, this);
  }

  // 在整个MyBatis初始化过程中用来初始化该插件的方法
  default void setProperties(Properties properties) {
    // NOP
  }

}
