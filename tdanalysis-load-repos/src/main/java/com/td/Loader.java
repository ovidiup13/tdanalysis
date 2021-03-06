package com.td;

import java.util.List;

import com.td.models.RepositoryModel;
import com.td.processor.RepositoryProcessor;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class Loader {

    public static void main(String[] args) {
        ApplicationContext context = new AnnotationConfigApplicationContext(Loader.class);

        RepositoryReader reader = context.getBean(RepositoryReader.class);
        List<RepositoryModel> repos = reader.readRepositories();

        RepositoryProcessor processor = context.getBean(RepositoryProcessor.class);
        processor.processRepositories(repos);
    }
}
