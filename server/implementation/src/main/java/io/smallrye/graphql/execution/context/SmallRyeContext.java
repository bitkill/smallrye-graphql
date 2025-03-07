package io.smallrye.graphql.execution.context;

import static io.smallrye.graphql.SmallRyeGraphQLServerMessages.msg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonBuilderFactory;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import graphql.ExecutionInput;
import graphql.GraphQLContext;
import graphql.ParseAndValidate;
import graphql.ParseAndValidateResult;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.SelectedField;
import io.smallrye.graphql.api.Context;
import io.smallrye.graphql.execution.QueryCache;
import io.smallrye.graphql.schema.model.Field;
import io.smallrye.graphql.schema.model.Operation;
import io.smallrye.graphql.schema.model.Type;

/**
 * Implements the Context from MicroProfile API.
 *
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class SmallRyeContext implements Context {

    private static final InheritableThreadLocal<SmallRyeContext> current = new InheritableThreadLocal<>();

    public static void register(JsonObject jsonInput) {
        SmallRyeContext registry = new SmallRyeContext(jsonInput);
        current.set(registry);
    }

    public static SmallRyeContext getContext() {
        return current.get();
    }

    public static void setContext(SmallRyeContext context) {
        current.set(context);
    }

    public SmallRyeContext withDataFromExecution(ExecutionInput executionInput) {
        return withDataFromExecution(executionInput, this.queryCache);
    }

    public SmallRyeContext withDataFromExecution(ExecutionInput executionInput, QueryCache queryCache) {
        return new SmallRyeContext(this.jsonObject, this.dfe, executionInput, queryCache, this.field, this.type);
    }

    public SmallRyeContext withDataFromFetcher(DataFetchingEnvironment dfe, Field field, Type type) {
        return new SmallRyeContext(this.jsonObject, dfe, this.executionInput, this.queryCache, field, type);
    }

    public static void remove() {
        current.remove();
    }

    @Override
    public JsonObject getRequest() {
        return jsonObject;
    }

    @Override
    public <T> T unwrap(Class<T> wrappedType) {
        // We only support DataFetchingEnvironment, ExecutionInput and Document at this point
        if (wrappedType.equals(DataFetchingEnvironment.class)) {
            return (T) this.dfe;
        } else if (wrappedType.equals(ExecutionInput.class)) {
            return (T) this.executionInput;
        } else if (wrappedType.equals(Document.class)) {
            return documentSupplier != null ? (T) documentSupplier.get() : null;
        }
        throw msg.unsupportedWrappedClass(wrappedType.getName());
    }

    @Override
    public Boolean hasArgument(String name) {
        if (dfe != null) {
            return dfe.containsArgument(name);
        }
        return null;
    }

    @Override
    public <T> T getArgument(String name) {
        if (dfe != null) {
            return dfe.getArgument(name);
        }
        return null;
    }

    @Override
    public Map<String, Object> getArguments() {
        if (dfe != null) {
            return dfe.getArguments();
        }
        return null;
    }

    @Override
    public String getPath() {
        if (dfe != null) {
            return dfe.getExecutionStepInfo().getPath().toString();
        }
        return null;
    }

    @Override
    public String getExecutionId() {
        if (dfe != null) {
            return dfe.getExecutionId().toString();
        } else if (executionInput != null) {
            return executionInput.getExecutionId().toString();
        }
        return null;
    }

    @Override
    public String getFieldName() {

        if (field != null) {
            return field.getName();
        }

        if (dfe != null) {
            return dfe.getField().getName();
        }
        return null;
    }

    @Override
    public <T> T getSource() {
        if (dfe != null) {
            return dfe.getSource();
        }
        return null;
    }

    @Override
    public JsonArray getSelectedFields(boolean includeSourceFields) {
        if (dfe != null) {
            DataFetchingFieldSelectionSet selectionSet = dfe.getSelectionSet();
            Set<SelectedField> fields = new LinkedHashSet<>(selectionSet.getFields());
            return toJsonArrayBuilder(fields, includeSourceFields).build();
        }
        return null;
    }

    @Override
    public String getOperationType() {
        if (dfe != null) {
            return getOperationTypeFromDefinition(dfe.getOperationDefinition());
        }
        return null;
    }

    @Override
    public List<String> getRequestedOperationTypes() {
        List<String> allRequestedTypes = new ArrayList<>();

        if (documentSupplier != null) {
            Document document = documentSupplier.get();
            List<OperationDefinition> definitions = document.getDefinitionsOfType(OperationDefinition.class);
            for (OperationDefinition definition : definitions) {
                String operationType = getOperationTypeFromDefinition(definition);
                if (!allRequestedTypes.contains(operationType)) {
                    allRequestedTypes.add(operationType);
                }
            }
        }
        return allRequestedTypes;
    }

    @Override
    public Optional<String> getParentTypeName() {
        if (dfe != null) {
            return getName(dfe.getParentType());
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> getOperationName() {
        // Check if it's set in the request
        if (hasOperationName()) {
            String fromRequest = jsonObject.getString(OPERATION_NAME);
            if (fromRequest != null && !fromRequest.isEmpty()) {
                return Optional.of(fromRequest);
            }
        }

        // Else try the input
        if (executionInput != null && executionInput.getOperationName() != null
                && !executionInput.getOperationName().isEmpty()) {
            return Optional.of(executionInput.getOperationName());
        }

        // Else try DataFetchingEnvironment
        if (dfe != null && dfe.getOperationDefinition() != null && dfe.getOperationDefinition().getName() != null
                && !dfe.getOperationDefinition().getName().isEmpty()) {
            return Optional.of(dfe.getOperationDefinition().getName());
        }

        return Optional.empty();
    }

    @Override
    public <K> boolean hasMetaData(K key) {
        if (dfe != null) {
            return dfe.getGraphQlContext().hasKey(key);
        }
        return false;
    }

    @Override
    public <K, V> V getMetaData(K key) {
        if (dfe != null) {
            return dfe.getGraphQlContext().get(key);
        }
        return null;
    }

    @Override
    public <K, V> void putMetaData(K key, V value) {
        if (dfe != null) {
            dfe.getGraphQlContext().put(key, value);
        }
    }

    @Override
    public <K> boolean hasLocalMetaData(K key) {
        if (dfe != null) {
            GraphQLContext localContext = dfe.getLocalContext();
            return localContext != null && localContext.hasKey(key);
        }
        return false;
    }

    @Override
    public <K, V> V getLocalMetaData(K key) {
        if (dfe != null) {
            GraphQLContext localContext = dfe.getLocalContext();
            if (localContext != null && localContext.hasKey(key)) {
                return localContext.get(key);
            }
        }
        return null;
    }

    @Override
    public <K, V> void putLocalMetaData(K key, V value) {
        if (dfe != null && dfe.getLocalContext() != null) {
            GraphQLContext localContext = dfe.getLocalContext();
            localContext.put(key, value);
        }
    }

    private Optional<String> getName(GraphQLType graphQLType) {
        if (graphQLType instanceof GraphQLNamedType) {
            return Optional.of(((GraphQLNamedType) graphQLType).getName());
        } else if (graphQLType instanceof GraphQLNonNull) {
            return getName(((GraphQLNonNull) graphQLType).getWrappedType());
        } else if (graphQLType instanceof GraphQLList) {
            return getName(((GraphQLList) graphQLType).getWrappedType());
        }
        return Optional.empty();
    }

    private String getOperationTypeFromDefinition(OperationDefinition definition) {
        return definition.getOperation().toString();
    }

    private final JsonObject jsonObject;
    private final DataFetchingEnvironment dfe;
    private final ExecutionInput executionInput;
    private final Supplier<Document> documentSupplier;
    private final Field field;
    private final Type type;
    private final QueryCache queryCache;

    public SmallRyeContext(final JsonObject jsonObject) {
        this.jsonObject = jsonObject;
        this.dfe = null;
        this.executionInput = null;
        this.queryCache = null;
        this.documentSupplier = null;
        this.field = null;
        this.type = null;
    }

    public SmallRyeContext(JsonObject jsonObject,
            DataFetchingEnvironment dfe,
            ExecutionInput executionInput,
            QueryCache queryCache,
            Field field,
            Type type) {
        this.jsonObject = jsonObject;
        this.dfe = dfe;
        this.field = field;
        this.type = type;
        this.executionInput = executionInput;
        this.queryCache = queryCache;
        this.documentSupplier = new DocumentSupplier(executionInput, queryCache);
    }

    private JsonArrayBuilder toJsonArrayBuilder(Set<SelectedField> fields, boolean includeSourceFields) {
        JsonArrayBuilder builder = jsonbuilder.createArrayBuilder();

        for (SelectedField field : fields) {
            if (!isFlattenScalar(field)) {
                if (includeSourceFields || !isSourceField(field)) {
                    if (isScalar(field)) {
                        builder = builder.add(field.getName());
                    } else {
                        builder = builder.add(toJsonObjectBuilder(field, includeSourceFields));
                    }
                }
            }
        }

        return builder;
    }

    private JsonObjectBuilder toJsonObjectBuilder(SelectedField selectedField, boolean includeSourceFields) {
        JsonObjectBuilder builder = jsonbuilder.createObjectBuilder();
        Set<SelectedField> fields = new LinkedHashSet<>(selectedField.getSelectionSet().getFields());
        builder = builder.add(selectedField.getName(),
                toJsonArrayBuilder(fields, includeSourceFields));
        return builder;
    }

    private boolean isSourceField(SelectedField selectedField) {
        // A source field is an operation
        if (type.hasOperations()) {
            Map<String, Operation> sourceFields = type.getOperations();
            String fieldName = selectedField.getName();
            if (sourceFields.containsKey(fieldName)) {
                Operation o = sourceFields.get(fieldName);
                return o.isSourceField();
            }
        }
        return false;
    }

    private boolean isScalar(SelectedField field) {
        List<GraphQLFieldDefinition> fieldDefinitions = field.getFieldDefinitions();
        for (GraphQLFieldDefinition fieldDefinition : fieldDefinitions) {
            GraphQLType graphQLType = unwrapGraphQLType(fieldDefinition.getType());
            if (isScalar(graphQLType)) {
                return true;
            }
        }
        return false;
    }

    private boolean isScalar(GraphQLType gqlt) {
        return GraphQLScalarType.class.isAssignableFrom(gqlt.getClass());
    }

    private GraphQLType unwrapGraphQLType(GraphQLType gqlt) {
        if (isNonNull(gqlt)) {
            GraphQLNonNull graphQLNonNull = (GraphQLNonNull) gqlt;
            return unwrapGraphQLType(graphQLNonNull.getWrappedType());
        } else if (isList(gqlt)) {
            GraphQLList graphQLList = (GraphQLList) gqlt;
            return unwrapGraphQLType(graphQLList.getWrappedType());
        }
        return gqlt;
    }

    private boolean isNonNull(GraphQLType gqlt) {
        return GraphQLNonNull.class.isAssignableFrom(gqlt.getClass());
    }

    private boolean isList(GraphQLType gqlt) {
        return GraphQLList.class.isAssignableFrom(gqlt.getClass());
    }

    private boolean isFlattenScalar(SelectedField field) {
        return field.getQualifiedName().contains("/");
    }

    @Override
    public String toString() {
        return "SmallRyeContext {\n"
                + "executionId = " + getExecutionId() + ",\n"
                + "request = " + getRequest() + ",\n"
                + "operationName = " + getOperationName().orElse(null) + ",\n"
                + "operationTypes = " + getRequestedOperationTypes() + ",\n"
                + "parentTypeName = " + getParentTypeName().orElse(null) + ",\n"
                + "variables = " + getVariables().orElse(null) + ",\n"
                + "query = " + getQuery() + ",\n"
                + "fieldName = " + getFieldName() + ",\n"
                + "selectedFields = " + getSelectedFields() + ",\n"
                + "source = " + getSource() + ",\n"
                + "arguments = " + getArguments() + ",\n"
                + "fieldName = " + getFieldName() + ",\n"
                + "path = " + getPath() + "\n"
                + "}";
    }

    private static final JsonBuilderFactory jsonbuilder = Json.createBuilderFactory(null);

    private static class DocumentSupplier implements Supplier<Document> {

        private final ExecutionInput executionInput;
        private final QueryCache queryCache;

        public DocumentSupplier(ExecutionInput executionInput,
                QueryCache queryCache) {
            this.executionInput = executionInput;
            this.queryCache = queryCache;
        }

        @Override
        public Document get() {
            if (queryCache == null) {
                ParseAndValidateResult parse = ParseAndValidate.parse(executionInput);
                return parse.isFailure() ? null : parse.getDocument();
            } else {
                PreparsedDocumentEntry documentEntry = queryCache.getDocument(executionInput, ei -> {
                    ParseAndValidateResult parse = ParseAndValidate.parse(ei);
                    return parse.isFailure() ? new PreparsedDocumentEntry(parse.getErrors())
                            : new PreparsedDocumentEntry(parse.getDocument());
                });
                return documentEntry.hasErrors() ? null : documentEntry.getDocument();
            }
        }
    }
}
