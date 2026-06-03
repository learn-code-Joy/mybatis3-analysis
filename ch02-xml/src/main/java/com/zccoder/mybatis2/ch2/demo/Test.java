package com.zccoder.mybatis2.ch2.demo;

import org.apache.ibatis.debug.learn.Demo;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.IOException;
import java.io.InputStream;

public class Test {
  public static void main(String[] args) throws IOException {
    String resource = "mybatis.cfg.xml";

//    InputStream inputStream = Resources.getResourceAsStream(resource);
    InputStream inputStream = Demo.class.getClassLoader().getResourceAsStream(resource);
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
    SqlSession sqlSession = sqlSessionFactory.openSession();
  }
}
