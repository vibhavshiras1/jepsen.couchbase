#!/bin/bash

function print_usage() {
  echo "$(basename "$0") - Create, stop, and destroy nodes using vagrant or docker
Usage: $(basename "$0") [options...]
Options:
  --type=STRING                 Type of nodes to provision:vagrant or docker
  --action=STRING               Action to take, i.e create, halt, destroy, etc
  --nodes=INT                   Number of nodes to take action against. Default=3
" >&2
}

NODES=${NODES:-3}

# parse input parameters
for i in "$@"
do
case $i in
    --type=*)
    TYPE="${i#*=}"
    ;;
    --action=*)
    ACTION="${i#*=}"
    ;;
    --nodes=*)
    NODES="${i#*=}"
    ;;
    --vm-os=*)
    VM_OS="${i#*=}"
    ;;
    -h|--help)
      print_usage
      exit 0
    ;;
    *)
      print_usage
      exit 1
    ;;
esac
done

if [ -z "$TYPE" ]; then
    echo "--type not provided"
    exit 1
fi

if [ -z "$ACTION" ]; then
    echo "--action not provided"
    exit 1
fi

case "$TYPE" in
    "vagrant")
        export VAGRANT_CWD=./resources
        case "$ACTION" in
            "create")
                if [[ NODES -eq 0 ]]; then
                    echo "--nodes must be greater than 0"
                fi

                if [ -z "$VM_OS" ]; then
                    echo "--vm-os not provided, defaulting to ubuntu1604"
                    VM_OS="ubuntu1604"
                else
                    case "$VM_OS" in
                    "ubuntu1604")
                    VM_OS="ubuntu1604"
                    ;;
                    "centos7")
                    VM_OS="centos7"
                    ;;
                    *)
                    echo "--vm-os choice not recognized, exiting"
                    exit 1
                    ;;
                    esac

                fi
                # create will ensure that the number of nodes requested is the number of nodes present
                # and will extract ips into nodes file
                currentNodes=0
                if [ -d "./resources/.vagrant/machines/" ]; then
                  currentNodes=$(ls -1U ./resources/.vagrant/machines/ | wc -l)
                fi

                if [[ currentNodes -eq 0 ]]; then
                    cp ./resources/vagrant/${VM_OS} ./resources/Vagrantfile
                fi


                if [[ NODES -ge currentNodes ]]; then
                    NODES=$NODES vagrant up
                else
                    for (( i=currentNodes; i>NODES; i-- ))
                    do
                        NODES=${i} vagrant destroy node${i} --force && rm -rf ./resources/.vagrant/machines/node${i}
                    done
		            NODES=$NODES vagrant up
                fi
                for (( i=1; i<=$(ls -1U ./resources/.vagrant/machines/ | wc -l); i++ ))
                do
                    if [ "$VM_OS" = "ubuntu1604" ]; then
                        if [[ i -eq 1 ]]; then
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet addr:[0-9]+(\.[0-9]+){3}" | cut -d ":" -f 2- >  ./nodes
                        else
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet addr:[0-9]+(\.[0-9]+){3}" | cut -d ":" -f 2- >>  ./nodes
                        fi
                    fi
                    if [ "$VM_OS" = "centos7" ]; then
                        if [[ i -eq 1 ]]; then
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet [0-9]+(\.[0-9]+){3}" | cut -d " " -f 2- >  ./nodes
                        else
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet [0-9]+(\.[0-9]+){3}" | cut -d " " -f 2- >>  ./nodes
                        fi
                    fi

                done
                ;;
            "halt-all")
                NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant halt
                ;;
            "resume-all")
                # resume-all will start all stopped vagrants in ./resources/.vagrant/machines and
                # extract the ips into the nodes file
                NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant up

                is_ubuntu1604=$(grep "ubuntu1604" ./resources/Vagrantfile)
                is_centos7=$(grep "centos7" ./resources/Vagrantfile)

                for (( i=1; i<=$(ls -1U ./resources/.vagrant/machines/ | wc -l); i++ ))
                do

                    if [ "$is_ubuntu1604" ]; then
                        if [[ i -eq 1 ]]; then
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet addr:[0-9]+(\.[0-9]+){3}" | cut -d ":" -f 2- >  ./nodes
                        else
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet addr:[0-9]+(\.[0-9]+){3}" | cut -d ":" -f 2- >>  ./nodes
                        fi
                    fi

                    if [ "$is_centos7" ]; then
                        if [[ i -eq 1 ]]; then
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet [0-9]+(\.[0-9]+){3}" | cut -d " " -f 2- >  ./nodes
                        else
                            NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant ssh node${i} -c "ifconfig eth1" | grep -o -E "inet [0-9]+(\.[0-9]+){3}" | cut -d " " -f 2- >>  ./nodes
                        fi
                    fi

                done
                ;;
            "destroy-all")
                NODES=$(ls -1U ./resources/.vagrant/machines/ | wc -l) vagrant destroy --force && rm -rf ./resources/.vagrant
                ;;
        esac
        ;;
    "docker")
        case "$ACTION" in
	    "create")
		(cd ./resources/docker/ && sh docker.sh --start --nodes=$NODES)
		;;
	    "halt-all")
		(cd ./resources/docker/ && sh docker.sh --stop)
		;;
	    "resume-all")
		(cd ./resources/docker/ && sh docker.sh --resume)
		;;
	    "destroy-all")
		(cd ./resources/docker/ && sh docker.sh --destroy)
		;;
	esac
        ;;
esac
