require "jira/api"

module Embulk
  module Input
    class JiraInputPlugin < InputPlugin
      Plugin.register_input("jira", self)

      def self.transaction(config, &control)
        # configuration code:
        attributes = extract_attributes(config.param("attributes", :array))

        task = {
          "username" => config.param("username", :string),
          "password" => config.param("password", :string),
          "uri" => config.param("uri", :string),
          "jql" => config.param("jql", :string),
          "attributes" => attributes,
        }

        columns = attributes.map.with_index do |(attribute_name, type), i|
          Column.new(i, attribute_name, type.to_sym)
        end

        resume(task, columns, 1, &control)
      end

      def self.resume(task, columns, count, &control)
        commit_reports = yield(task, columns, count)

        next_config_diff = {}
        return next_config_diff
      end

      # TODO
      #def self.guess(config)
      #  sample_records = [
      #    {"example"=>"a", "column"=>1, "value"=>0.1},
      #    {"example"=>"a", "column"=>2, "value"=>0.2},
      #  ]
      #  columns = Guess::SchemaGuess.from_hash_records(sample_records)
      #  return {"columns" => columns}
      #end

      def self.extract_attributes(attribute_names)
        unsupported_attributes = []
        attribute_names.each do |attribute_name|
          unless Jira::Issue::SUPPORTED_ATTRIBUTES.include?(attribute_name)
            unsupported_attributes << attribute_name
          end
        end

        unless unsupported_attributes.empty?
          unsupported_attribute_names =
            unsupported_attributes.map {|attr| "'#{attr}'"}.join(', ')

          raise(<<-MESSAGE)
Unsupported Jira attributes is(are) specified.
We support #{Jira::Issue::SUPPORTED_ATTRIBUTE_NAMES}, but your config includes #{unsupported_attribute_names}.
          MESSAGE
        end

        attribute_names.map do |name|
          type = Jira::Issue.detect_attribute_type(name)
          [name, type]
        end
      end

      def init
        # initialization code:
        @attributes = task["attributes"]
        @jira = Jira::Api.setup do |config|
          config.username = task["username"]
          config.password = task["password"]
          config.uri = task["uri"]
          config.api_version = "latest"
          config.auth_type = :basic
        end
      end

      def run
        @jira.search_issues(task["jql"]).each do |issue|
          values = @attributes.map do |attribute_name, _|
            issue[attribute_name]
          end
          page_builder.add(values)
        end
        page_builder.finish

        commit_report = {}
        return commit_report
      end
    end
  end
end
